"""AniList list import.

Lives in `library` rather than its own module because it creates user_media rows — library's
job — and needs library's service functions. A separate `imports` module would preserve the
four-file pattern at the cost of an empty models.py: a module with no data is a service, not a
module.

Read-only and one-way, permanently. No OAuth: MediaListCollection is readable anonymously for
public profiles, and AniList requires a token only for mutations and private data.
"""

import logging
import uuid
from collections.abc import Mapping

from sqlalchemy.ext.asyncio import AsyncSession

from app.library import service
from app.library.models import UserMediaStatus
from app.library.schemas import ImportSummary
from app.media import service as media_service
from app.media.models import MediaSource
from app.media.providers.base import ListEntryStatus, MediaProvider, UserListProvider
from app.media.service import MediaSourceNotConfigured

logger = logging.getLogger(__name__)

# The anti-corruption translation, and the only place it happens. ListEntryStatus is the
# provider-neutral vocabulary that `media` may speak; UserMediaStatus is the domain's. They agree
# member for member today, and this dict is what lets either change without the other — and what
# keeps `media` from importing `library`, which would invert the dependency into a cycle.
_STATUS: dict[ListEntryStatus, UserMediaStatus] = {
    ListEntryStatus.WATCHING: UserMediaStatus.WATCHING,
    ListEntryStatus.PLANNED: UserMediaStatus.PLANNED,
    ListEntryStatus.COMPLETED: UserMediaStatus.COMPLETED,
    ListEntryStatus.DROPPED: UserMediaStatus.DROPPED,
    ListEntryStatus.PAUSED: UserMediaStatus.PAUSED,
}

if set(_STATUS) != set(ListEntryStatus):  # pragma: no cover - import-time guard
    # A hand-written mapping over an enum silently loses a member the day someone adds one: the
    # mapper would happily emit it and `_STATUS[entry.status]` would KeyError mid-import, after
    # the provider round trip and partway through building rows. A bare `assert` would be
    # stripped under `python -O`, bringing the KeyError back in exactly the environment where you
    # least want it.
    raise RuntimeError("every ListEntryStatus needs a UserMediaStatus mapping")


async def import_anilist_library(
    session: AsyncSession,
    providers: Mapping[MediaSource, MediaProvider],
    *,
    user_id: uuid.UUID,
    username: str,
) -> ImportSummary:
    """Fetch a public AniList list and insert whatever is missing from this user's library.

    Two bulk statements, not one per title: AniList embeds the full media object in every list
    entry, so the payload is already in hand. Routing it through get_or_create_media would turn
    one request into 401.

    The caller owns the transaction, so every chunk of both statements commits or rolls back
    together: a failure halfway through leaves no partial library and a retry is a clean re-run.
    """
    provider = providers.get(MediaSource.ANILIST)
    if not isinstance(provider, UserListProvider):
        # Unreachable today — build_registry registers AniList unconditionally, since it needs no
        # API key — but the registry's static type is Mapping[MediaSource, MediaProvider], which
        # genuinely does not know AniList is special.
        raise MediaSourceNotConfigured("no AniList provider is registered")

    # Decision 4-M: release the transaction get_current_user's read opened, before spending up to
    # MAX_LIST_CHUNKS requests upstream. Holding a pooled connection idle-in-transaction across
    # that is the objection 4-A raised against the advisory lock.
    #
    # `user_id` is already an evaluated UUID parameter here, so unlike the add path there is no
    # ORM attribute left to be expired by this rollback.
    await session.rollback()

    user_list = await provider.fetch_user_list(username)
    if not user_list.entries:
        return ImportSummary(imported=0, skipped=0, failed=user_list.dropped, truncated=user_list.truncated)

    media_ids = await media_service.persist_media_bulk(session, [entry.media for entry in user_list.entries])
    rows = [
        {
            "media_id": media_ids[entry.media.ref],
            "status": _STATUS[entry.status],
            "score": entry.score,
            "progress": entry.progress,
        }
        for entry in user_list.entries
    ]

    imported = await service.bulk_add_entries(session, user_id=user_id, rows=rows)
    logger.info(
        "imported AniList list for %r: %d new, %d already present, %d unmappable",
        username,
        imported,
        len(rows) - imported,
        user_list.dropped,
    )
    # `skipped` is derived rather than counted separately, so the three fields are incapable of
    # disagreeing with each other.
    return ImportSummary(
        imported=imported,
        skipped=len(rows) - imported,
        failed=user_list.dropped,
        truncated=user_list.truncated,
    )
