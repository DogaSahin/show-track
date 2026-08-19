from collections.abc import Mapping
from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.media import service
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider
from app.media.schemas import MediaSearchResponse

router = APIRouter(prefix="/media", tags=["media"])

# Depends lives inside Annotated, not in a default value: ruff's B008 flags
# `= Depends(get_providers)` but not this form. Same convention as app/users/routes.py.
ProvidersDep = Annotated[Mapping[MediaSource, MediaProvider], Depends(get_providers)]


@router.get("/search", response_model=MediaSearchResponse)
async def search_media(
    providers: ProvidersDep,
    q: Annotated[str, Query(min_length=1, max_length=100)],
    # le=500 mirrors TMDB's hard cap on page numbers; asking for 501 is a client bug, not a
    # provider error to surface.
    page: Annotated[int, Query(ge=1, le=500)] = 1,
) -> MediaSearchResponse:
    return await service.search_media(providers, q, page)
