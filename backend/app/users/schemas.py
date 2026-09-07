import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, EmailStr, Field


class RegisterRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    email: EmailStr
    # 8 is the OWASP minimum. 256 is request-body sanity, not a column bound — the plaintext
    # password is never stored; only its argon2 hash is (User.hashed_password). That hash is
    # 97 characters at argon2-cffi's default parameters ($argon2id$v=19$m=65536,t=3,p=4$ (30)
    # + '$' + 22 b64 chars for a 16-byte salt + '$' + 43 b64 chars for a 32-byte hash),
    # regardless of input length — but it moves if those parameters ever change (e.g. a bigger
    # salt or memory cost), not just with input size. Argon2 itself has no input-length limit,
    # verified by hashing and verifying a 1 MB password successfully.
    password: str = Field(min_length=8, max_length=256)
    # Bounded to match JoinGroupRequest, and for a reason that only arrived with Phase 7.5a:
    # this field used to be compared against one server secret, and is now looked up in a
    # table after invites.normalise_code builds two full copies of it. Unbounded input on an
    # UNAUTHENTICATED endpoint with no rate limiting anywhere is work an anonymous caller gets
    # to choose the size of. No pattern, for JoinGroupRequest's reason: normalisation exists to
    # accept a hyphenated or lowercase code, so rejecting one here would 422 the very input it
    # was written for. 64 also fits a `openssl rand -hex 32` REGISTRATION_CODE exactly.
    invite_code: str = Field(min_length=1, max_length=64)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class RefreshRequest(BaseModel):
    refresh_token: str


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    username: str
    email: str
    created_at: datetime
