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
from app.media.providers.base import MediaProvider
from app.media.schemas import MediaDetail, MediaSearchResponse

router = APIRouter(prefix="/media", tags=["media"])

# Depends lives inside Annotated, not in a default value: ruff's B008 flags
# `= Depends(get_providers)` but not this form. Same convention as app/users/routes.py.
ProvidersDep = Annotated[Mapping[MediaSource, MediaProvider], Depends(get_providers)]
SessionDep = Annotated[AsyncSession, Depends(get_session)]


@router.get("/search", response_model=MediaSearchResponse)
async def search_media(
    providers: ProvidersDep,
    q: Annotated[str, Query(min_length=1, max_length=100)],
    # le=500 mirrors TMDB's hard cap on page numbers; asking for 501 is a client bug, not a
    # provider error to surface.
    page: Annotated[int, Query(ge=1, le=500)] = 1,
) -> MediaSearchResponse:
    return await service.search_media(providers, q, page)


# Declared AFTER /search on purpose. FastAPI matches in declaration order, so a parameterised
# route above /search would capture it and 422 on "search" failing UUID validation.
@router.get("/{media_id}", response_model=MediaDetail)
async def read_media(media_id: uuid.UUID, session: SessionDep) -> MediaDetail:
    detail = await service.get_media_detail(session, media_id, datetime.now(tz=UTC))
    if detail is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="media not found")
    return detail
