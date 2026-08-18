from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.users import security
from app.users.models import User

# auto_error=False so a missing header reaches this function rather than FastAPI's own 403,
# which keeps every unauthenticated outcome a 401 with one body.
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
