import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.users import security
from app.users.models import RefreshToken, User


async def create_user(session: AsyncSession, *, username: str, email: str, hashed_password: str) -> User:
    """Insert a user and return it with its generated id populated.

    Takes an already-hashed password — Phase 2.1 owns hashing. Flushes rather than commits:
    the caller owns the transaction boundary, which is what lets a route compose several
    writes into one atomic unit.
    """
    user = User(username=username, email=email, hashed_password=hashed_password)
    session.add(user)
    await session.flush()
    return user


class RegistrationError(Exception):
    """Raised for a bad invite code or a duplicate username/email."""

    def __init__(self, status_code: int, detail: str) -> None:
        self.status_code = status_code
        self.detail = detail
        super().__init__(detail)


async def register_user(session: AsyncSession, *, username: str, email: str, password: str, invite_code: str) -> User:
    if invite_code != get_settings().registration_code:
        # Deliberately generic: never state whether the code was wrong or merely absent.
        raise RegistrationError(400, "invalid invite code")

    try:
        return await create_user(
            session, username=username, email=email, hashed_password=security.hash_password(password)
        )
    except IntegrityError as exc:
        await session.rollback()
        raise RegistrationError(409, "username or email already registered") from exc


async def authenticate(session: AsyncSession, *, email: str, password: str) -> User | None:
    """Returns the user, or None for both an unknown email and a wrong password.

    The lookup is `lower(email) = lower(:input)` because Phase 1 made email uniqueness
    case-insensitive with a functional index; plain equality misses that index and is
    semantically wrong.

    When no user matches we still verify against DUMMY_PASSWORD_HASH. Skipping that would let
    an unknown email return in microseconds while a wrong password pays the tens-of-
    milliseconds cost of a real argon2 verify (order of magnitude measured; see
    `security.DUMMY_PASSWORD_HASH` — the exact figure varies with machine load and isn't a
    constant of the code), which makes the endpoint an account-existence oracle for anyone
    with a stopwatch.
    """
    result = await session.execute(select(User).where(func.lower(User.email) == func.lower(email)))
    user = result.scalar_one_or_none()

    if user is None:
        security.verify_password(security.DUMMY_PASSWORD_HASH, password)
        return None

    if not security.verify_password(user.hashed_password, password):
        return None

    return user


async def issue_token_pair(session: AsyncSession, user: User, *, family_id: uuid.UUID | None = None) -> tuple[str, str]:
    """Mint an access token and a refresh token, persisting only the refresh token's hash."""
    settings = get_settings()
    refresh = security.generate_refresh_token()
    session.add(
        RefreshToken(
            user_id=user.id,
            family_id=family_id or uuid.uuid4(),
            token_hash=security.hash_refresh_token(refresh),
            expires_at=datetime.now(UTC) + timedelta(days=settings.refresh_token_ttl_days),
        )
    )
    await session.flush()
    return security.create_access_token(user.id), refresh


async def _find_refresh_token(session: AsyncSession, raw_token: str) -> RefreshToken | None:
    result = await session.execute(
        select(RefreshToken).where(RefreshToken.token_hash == security.hash_refresh_token(raw_token))
    )
    return result.scalar_one_or_none()


async def rotate_refresh_token(session: AsyncSession, raw_token: str) -> tuple[str, str] | None:
    """Issue a new pair and revoke the presented token. Returns None if it cannot be used.

    A token that is already revoked means someone replayed one — the legitimate client would
    be holding the newest token, not this one. Every token in the family is revoked in
    response, which logs out the thief and the real user together. That is the intended
    outcome: it is the only way to be sure the thief is out.
    """
    stored = await _find_refresh_token(session, raw_token)
    if stored is None:
        return None

    now = datetime.now(UTC)

    if stored.revoked_at is not None:
        await session.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == stored.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        return None

    if stored.expires_at <= now:
        return None

    stored.revoked_at = now
    user = await session.get(User, stored.user_id)
    if user is None:
        return None
    return await issue_token_pair(session, user, family_id=stored.family_id)


async def revoke_refresh_token(session: AsyncSession, raw_token: str) -> None:
    """Idempotent by design: an unknown or already-revoked token is a no-op, so retries work
    and the caller cannot learn whether the token existed.
    """
    stored = await _find_refresh_token(session, raw_token)
    if stored is not None and stored.revoked_at is None:
        stored.revoked_at = datetime.now(UTC)
