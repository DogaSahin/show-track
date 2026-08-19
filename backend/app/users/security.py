import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import Argon2Error, InvalidHashError

from app.config import get_settings

_ALGORITHM = "HS256"

_hasher = PasswordHasher()

# Verified against this hash when no user matches the submitted email, so that an unknown
# address costs the same as a wrong password. Without it the endpoint answers "does this
# account exist?" in the time it takes to reply: a lookup miss returns in microseconds
# against tens of milliseconds for a real verify (argon2-cffi 25.1.0 at default parameters;
# order of magnitude measured — the exact figure varies with machine load and isn't a constant
# of the code).
DUMMY_PASSWORD_HASH = _hasher.hash("dummy-password-for-constant-time-login")


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(hashed: str, password: str) -> bool:
    """False for a wrong password or a malformed/unparseable stored hash, never raises.

    A wrong password raises `VerifyMismatchError`, a subclass of `Argon2Error`. A stored
    string that isn't a well-formed argon2 hash raises `InvalidHashError` instead — verified
    against argon2-cffi 25.1.0's source: `InvalidHashError` subclasses `ValueError` directly,
    not `Argon2Error`, so both must be caught explicitly.
    """
    try:
        return _hasher.verify(hashed, password)
    except (Argon2Error, InvalidHashError):
        return False


def create_access_token(user_id: uuid.UUID) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_id),
        "iat": now,
        "exp": now + timedelta(minutes=settings.access_token_ttl_minutes),
    }
    # PyJWT 2.x returns str; 1.x returned bytes. Pinned at >=2.13 in requirements.txt.
    return jwt.encode(payload, settings.secret_key, algorithm=_ALGORITHM)


def decode_access_token(token: str) -> uuid.UUID | None:
    """Returns the subject, or None for any invalid token.

    `jwt.InvalidTokenError` is the common ancestor of every failure `jwt.decode` raises for a
    bad token — expired, bad signature, malformed, wrong algorithm (verified against PyJWT
    2.13.0's exception hierarchy). Catching the ancestor means a new failure mode in a future
    release fails closed.

    `algorithms=[...]` is required by this PyJWT version: calling `decode` without it raises
    `DecodeError` immediately rather than accepting the token (verified). Passing it explicitly
    also pins verification to HS256, so a token claiming a different algorithm is rejected
    rather than silently accepted.
    """
    try:
        payload = jwt.decode(token, get_settings().secret_key, algorithms=[_ALGORITHM])
        return uuid.UUID(payload["sub"])
    except (jwt.InvalidTokenError, KeyError, ValueError):
        return None


def generate_refresh_token() -> str:
    """43 characters of URL-safe base64 over 32 random bytes (measured)."""
    return secrets.token_urlsafe(32)


def hash_refresh_token(token: str) -> str:
    """SHA-256, deliberately not argon2.

    Argon2 is slow on purpose because passwords are low-entropy and brute-forcible. A refresh
    token is already 32 bytes of CSPRNG output, so there is nothing to brute-force and the
    tens of milliseconds an argon2 verify costs would be paid on every refresh for no gain.
    Hashing at all is what keeps a database leak from handing over live sessions.
    """
    return hashlib.sha256(token.encode()).hexdigest()
