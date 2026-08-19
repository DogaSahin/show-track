from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.users import security
from app.users.models import User

# auto_error=False turns both of HTTPBearer's own raise sites into `return None` instead
# (fastapi.security.http.HTTPBearer.__call__, fastapi 0.141.1: one site for a missing or
# malformed Authorization header — including one with no space, so no scheme or credentials parse
# out — the other for a header with a scheme other than "bearer"; both are 401 there too, never
# 403). The point of turning that off is the body, not the status: every case it would otherwise
# handle itself now falls through to the single _UNAUTHENTICATED raise below instead, so the
# detail string and headers stay under this module's control rather than FastAPI's own
# "Not authenticated" leaking through for some inputs and ours for others.
_bearer = HTTPBearer(auto_error=False)

_UNAUTHENTICATED = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED,
    detail="not authenticated",
    headers={"WWW-Authenticate": "Bearer"},
)


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> User:
    """Turn a bearer access token into a User, or 401.

    Every failure — absent header, wrong scheme, malformed token, bad signature, expired, or
    a subject that no longer exists — produces the identical response. Distinguishing them
    would tell an attacker which of their guesses was closer.
    """
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _UNAUTHENTICATED

    user_id = security.decode_access_token(credentials.credentials)
    if user_id is None:
        raise _UNAUTHENTICATED

    user = await session.get(User, user_id)
    if user is None:
        raise _UNAUTHENTICATED

    return user
