from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.pagination import InvalidCursor, decode_cursor
from app.recommendations import service
from app.recommendations.schemas import RecommendationPage
from app.users.dependencies import get_current_user
from app.users.models import User

router = APIRouter(prefix="/recommendations", tags=["recommendations"])

# Depends inside Annotated, not as a default value: ruff B008. Same convention as library/routes.py.
SessionDep = Annotated[AsyncSession, Depends(get_session)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]


@router.get("", response_model=RecommendationPage, responses={400: {"description": "unusable cursor"}})
async def list_recommendations(
    session: SessionDep,
    current_user: CurrentUserDep,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    # Capped for the same reason as /v1/library's: decode_cursor contains RecursionError, but not
    # paying for a megabyte of nesting is cheaper than containing it.
    cursor: Annotated[str | None, Query(max_length=2048)] = None,
) -> RecommendationPage:
    """The ranking is rebuilt here, and ONLY when no cursor was supplied.

    That single condition is the whole of the cursor-stability guarantee: a paginating client
    cannot reach the recompute branch, so the ranking it is walking cannot move underneath it. No
    generation column, no cleanup job, no expiring cursors.
    """
    now = datetime.now(tz=UTC)
    decoded = None

    if cursor is None:
        await service.ensure_fresh(session, user_id=current_user.id, now=now)
    else:
        try:
            decoded = decode_cursor(cursor, service.SORT_KEY, service.parse_rank)
        except InvalidCursor as exc:
            # A fixed detail, not str(exc): the message would echo client-supplied cursor content
            # straight back.
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid cursor") from exc

    items, next_cursor = await service.list_page(session, user_id=current_user.id, limit=limit, cursor=decoded, now=now)
    return RecommendationPage(items=items, next_cursor=next_cursor)
