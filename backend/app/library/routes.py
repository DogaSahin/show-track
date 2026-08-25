import uuid
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.library import import_service, service
from app.library.models import UserMediaStatus
from app.library.schemas import (
    AddLibraryEntryRequest,
    CreateReviewRequest,
    ImportRequest,
    ImportSummary,
    LibraryEntry,
    LibraryPage,
    LibrarySort,
    ReviewRead,
    UpdateLibraryEntryRequest,
    UpdateReviewRequest,
)
from app.media import service as media_service
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider, MediaRef
from app.pagination import InvalidCursor, decode_cursor
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
    # Measured both ways: removing this line fails two tests in tests/test_library_routes.py,
    # AND fails against a production-shaped session outside the harness. An earlier review claimed
    # conftest's savepoint-joined session hides the expiry; it does not.
    user_id = current_user.id

    media = await media_service.get_or_create_media(
        session, providers, MediaRef(source=payload.source, external_id=payload.external_id)
    )
    entry, created = await service.add_entry(session, user_id=user_id, media_id=media.id)
    await session.commit()

    if not created:
        response.status_code = status.HTTP_200_OK
    return service.to_entry(entry, media, datetime.now(tz=UTC))


@router.get("", response_model=LibraryPage, responses={400: {"description": "unusable cursor"}})
async def list_library(
    session: SessionDep,
    current_user: CurrentUserDep,
    # Named status_filter because `status` is already the imported fastapi.status module here.
    # The alias is what the client actually sends.
    status_filter: Annotated[UserMediaStatus | None, Query(alias="status")] = None,
    sort: LibrarySort = LibrarySort.TITLE,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    # Capped: decode_cursor contains RecursionError, but not paying for a megabyte of nesting in
    # the first place is cheaper than containing it.
    cursor: Annotated[str | None, Query(max_length=2048)] = None,
) -> LibraryPage:
    spec = service.SORTS[sort]
    decoded = None
    if cursor is not None:
        try:
            decoded = decode_cursor(cursor, sort.value, spec.parse)
        except InvalidCursor as exc:
            # A fixed detail, not str(exc): the message would echo client-supplied cursor content
            # straight back. Local rather than in app/errors.py because it has exactly one call
            # site.
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid cursor") from exc

    items, next_cursor = await service.list_entries(
        session,
        user_id=current_user.id,
        sort=sort,
        limit=limit,
        status=status_filter,
        cursor=decoded,
        now=datetime.now(tz=UTC),
    )
    return LibraryPage(items=items, next_cursor=next_cursor)


@router.post(
    "/import/anilist",
    response_model=ImportSummary,
    # UPSTREAM_RESPONSES first: it carries its own 404 ("no such title upstream"), and a later
    # key wins in a dict literal. Spread the other way round and this endpoint publishes the
    # wrong meaning for its most important error — measured, not theorised.
    responses={**UPSTREAM_RESPONSES, 404: {"description": "no public AniList list for that username"}},
)
async def import_from_anilist(
    payload: ImportRequest,
    session: SessionDep,
    providers: ProvidersDep,
    current_user: CurrentUserDep,
) -> ImportSummary:
    """Synchronous (decision 4-H). A typical list is a few hundred titles: one or two GraphQL
    requests plus a handful of INSERTs. A background job would need a task table, a polling
    endpoint and a status model to shave seconds off something run roughly once.

    user_id comes from the token, never the body, so the import can only ever write to the
    caller's own library. Read before the call for the same reason as add_to_library: 4-M's
    rollback expires the identity map.
    """
    user_id = current_user.id
    summary = await import_service.import_anilist_library(
        session, providers, user_id=user_id, username=payload.username
    )
    await session.commit()
    return summary


_ENTRY_NOT_FOUND = "library entry not found"


# Declared after the collection routes above. Ownership failures answer 404 rather than 403: a
# 403 on another user's entry id confirms that entry exists.
@router.patch("/{entry_id}", response_model=LibraryEntry, responses={404: {"description": _ENTRY_NOT_FOUND}})
async def update_library_entry(
    entry_id: uuid.UUID,
    payload: UpdateLibraryEntryRequest,
    session: SessionDep,
    current_user: CurrentUserDep,
) -> LibraryEntry:
    found = await service.get_entry(session, entry_id=entry_id, user_id=current_user.id)
    if found is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_ENTRY_NOT_FOUND)
    entry, media = found

    # exclude_unset, never exclude_none: `{"score": null}` unrates a title and must stay
    # distinguishable from a body that omits score entirely.
    await service.update_entry(session, entry, payload.model_dump(exclude_unset=True))
    await session.commit()
    return service.to_entry(entry, media, datetime.now(tz=UTC))


@router.delete(
    "/{entry_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    responses={404: {"description": _ENTRY_NOT_FOUND}},
)
async def remove_from_library(entry_id: uuid.UUID, session: SessionDep, current_user: CurrentUserDep) -> None:
    found = await service.get_entry(session, entry_id=entry_id, user_id=current_user.id)
    if found is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_ENTRY_NOT_FOUND)

    await service.delete_entry(session, found[0])
    await session.commit()


# A router of its own, mounted alongside the library one: a review is about a title, not about
# your library entry for it — you can review something you never tracked.
reviews_router = APIRouter(prefix="/reviews", tags=["reviews"])

_REVIEW_NOT_FOUND = "no such review"


@reviews_router.post(
    "",
    response_model=ReviewRead,
    status_code=status.HTTP_201_CREATED,
    responses={404: {"description": "no such title"}, 409: {"description": "you have already reviewed this title"}},
)
async def create_review(payload: CreateReviewRequest, session: SessionDep, current_user: CurrentUserDep) -> ReviewRead:
    try:
        review = await service.create_review(
            session,
            user_id=current_user.id,
            media_id=payload.media_id,
            body=payload.body,
            contains_spoilers=payload.contains_spoilers,
        )
    except service.ReviewExists as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="you have already reviewed this title"
        ) from exc
    except service.MediaMissing as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no such title") from exc
    await session.commit()
    # current_user IS the author on every own-review route, so the nested author costs no query.
    return service.to_review_read(review, current_user)


# Ownership failures answer 404 rather than 403, for the same reason the library routes above do:
# a 403 on someone else's review id confirms that review exists.
@reviews_router.patch("/{review_id}", response_model=ReviewRead, responses={404: {"description": _REVIEW_NOT_FOUND}})
async def update_review(
    review_id: uuid.UUID,
    payload: UpdateReviewRequest,
    session: SessionDep,
    current_user: CurrentUserDep,
) -> ReviewRead:
    review = await service.get_own_review(session, review_id=review_id, user_id=current_user.id)
    if review is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_REVIEW_NOT_FOUND)

    await service.update_review(session, review, payload.model_dump(exclude_unset=True))
    await session.commit()
    # current_user IS the author on every own-review route, so the nested author costs no query.
    return service.to_review_read(review, current_user)


@reviews_router.delete(
    "/{review_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    responses={404: {"description": _REVIEW_NOT_FOUND}},
)
async def delete_review(review_id: uuid.UUID, session: SessionDep, current_user: CurrentUserDep) -> Response:
    review = await service.get_own_review(session, review_id=review_id, user_id=current_user.id)
    if review is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_REVIEW_NOT_FOUND)

    await service.delete_review(session, review)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
