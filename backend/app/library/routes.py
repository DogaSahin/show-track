from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.library import service
from app.library.schemas import AddLibraryEntryRequest, LibraryEntry
from app.media import service as media_service
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider, MediaRef
from app.users.dependencies import get_current_user
from app.users.models import User

router = APIRouter(prefix="/library", tags=["library"])

# Depends lives inside Annotated, not in a default value: ruff's B008 flags
# `= Depends(get_session)` but not this form. Same convention as app/media/routes.py.
SessionDep = Annotated[AsyncSession, Depends(get_session)]
ProvidersDep = Annotated[Mapping[MediaSource, MediaProvider], Depends(get_providers)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]

# Declared for OpenAPI rather than handled here. app/errors.py owns the mapping; documenting the
# statuses on the route is what keeps that mapping discoverable from the call site.
UPSTREAM_RESPONSES: dict[int | str, dict[str, str]] = {
    404: {"description": "no such title upstream"},
    429: {"description": "the upstream provider rate limited this server"},
    502: {"description": "the upstream provider is unavailable"},
    503: {"description": "no provider configured for that source"},
    504: {"description": "the upstream provider timed out"},
}


@router.post(
    "",
    response_model=LibraryEntry,
    status_code=status.HTTP_201_CREATED,
    responses={200: {"description": "already in the library, returned untouched"}, **UPSTREAM_RESPONSES},
)
async def add_to_library(
    payload: AddLibraryEntryRequest,
    session: SessionDep,
    providers: ProvidersDep,
    current_user: CurrentUserDep,
    response: Response,
) -> LibraryEntry:
    """Idempotent (decision 4-D): adding a title already tracked returns it untouched with 200.

    Nothing here is caught. app/errors.py turns MediaNotFound into 404, MediaSourceNotConfigured
    into 503, and the ProviderError family into 502/504/429.
    """
    # Read the id BEFORE the call below. get_or_create_media rolls back (decision 4-M), and
    # Session.rollback() expires every persistent object in the identity map — including its
    # PRIMARY KEY. `current_user` was loaded by get_current_user in this same session, so
    # touching `current_user.id` afterwards is an expired-attribute load, which in async code is
    # MissingGreenlet: a 500 on the ordinary add path.
    #
    # The test suite CANNOT catch this: conftest's savepoint-joined session makes rollback() a
    # savepoint rollback, which does not expire. Verified separately against a production-shaped
    # session.
    user_id = current_user.id

    media = await media_service.get_or_create_media(
        session, providers, MediaRef(source=payload.source, external_id=payload.external_id)
    )
    entry, created = await service.add_entry(session, user_id=user_id, media_id=media.id)
    await session.commit()

    if not created:
        response.status_code = status.HTTP_200_OK
    return service.to_entry(entry, media, datetime.now(tz=UTC))
