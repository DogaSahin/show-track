from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.users import security
from app.users.models import User

# auto_error=False so a missing/empty header returns None instead of HTTPBearer raising its own
# 401 (fastapi.security.http.HTTPBase.make_not_authenticated_error, fastapi 0.141.1 — both
# auto_error branches are 401, never 403). The point of turning that off is the body, not the
# status: every unauthenticated outcome then falls through to the single _UNAUTHENTICATED raise
# below, so the detail string and headers stay under this module's control instead of FastAPI's
# own "Not authenticated" leaking through for the one case (no header at all) it would otherwise
# handle itself.
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
