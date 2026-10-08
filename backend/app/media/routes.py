import uuid
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.media import service
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider, MediaRef
from app.media.schemas import EpisodeList, MediaDetail, MediaSearchResponse, ResolveMediaRequest
from app.users.dependencies import get_current_user
from app.users.models import User

router = APIRouter(prefix="/media", tags=["media"])

# Depends lives inside Annotated, not in a default value: ruff's B008 flags
# `= Depends(get_providers)` but not this form. Same convention as app/users/routes.py.
ProvidersDep = Annotated[Mapping[MediaSource, MediaProvider], Depends(get_providers)]
SessionDep = Annotated[AsyncSession, Depends(get_session)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]


@router.get("/search", response_model=MediaSearchResponse)
async def search_media(
    providers: ProvidersDep,
    session: SessionDep,
    current_user: CurrentUserDep,
    q: Annotated[str, Query(min_length=1, max_length=100)],
    # le=500 mirrors TMDB's hard cap on page numbers; asking for 501 is a client bug, not a
    # provider error to surface.
    page: Annotated[int, Query(ge=1, le=500)] = 1,
) -> MediaSearchResponse:
    """Each item says whether it is stored (`media_id`) and whether the caller tracks it
    (`library_entry`). Read the id before the service ends the auth read (decision 4-M).
    """
    user_id = current_user.id
    return await service.search_with_library_state(session, providers, user_id=user_id, query=q, page=page)


@router.post("/resolve", response_model=MediaDetail)
async def resolve_media(payload: ResolveMediaRequest, session: SessionDep, providers: ProvidersDep) -> MediaDetail:
    """Opens a search result without adding it: the title's stored row, created if need be.
    POST because it can write a media row. Errors map as for POST /v1/library (app/errors.py):
    unknown title 404, provider not configured 503, provider failures 502/504/429.
    """
    detail = await service.resolve_media(
        session, providers, MediaRef(source=payload.source, external_id=payload.external_id), datetime.now(tz=UTC)
    )
    await session.commit()
    return detail


# Declared AFTER /search on purpose. FastAPI matches in declaration order, so a parameterised
# route above /search would capture it and 422 on "search" failing UUID validation.
@router.get("/{media_id}", response_model=MediaDetail)
async def read_media(media_id: uuid.UUID, session: SessionDep) -> MediaDetail:
    detail = await service.get_media_detail(session, media_id, datetime.now(tz=UTC))
    if detail is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="media not found")
    return detail


@router.get("/{media_id}/episodes", response_model=EpisodeList)
async def read_episodes(media_id: uuid.UUID, session: SessionDep) -> EpisodeList:
    """Seasons and episodes from the database; never a provider call."""
    episodes = await service.get_episode_list(session, media_id, datetime.now(tz=UTC))
    if episodes is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="media not found")
    return episodes
