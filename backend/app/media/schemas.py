import uuid
from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel

from app.media.models import MediaSource, MediaStatus, MediaType


class SourceStatus(StrEnum):
    """Per-provider health for one search. Reported for EVERY MediaSource, so a client can tell
    "this provider returned nothing" from "this server has no key for it".
    """

    OK = "ok"
    TIMEOUT = "timeout"
    RATE_LIMITED = "rate_limited"
    ERROR = "error"
    NOT_CONFIGURED = "not_configured"


class MediaSummary(BaseModel):
    """A search result. Identified by (source, external_id) rather than an internal id: search
    is transient and writes nothing, so there is no row to have an id yet.
    """

    source: MediaSource
    external_id: str
    type: MediaType
    title: str
    year: int | None
    genres: list[str]
    cover_image_url: str | None


class PersistedMedia(MediaSummary):
    """A title that has a row. Unlike MediaSummary it carries an internal id, because a row
    exists — and a client needs that id to act on the title (add it to the library, open it).

    Everything a title always knows about itself, and nothing that goes stale. Served wherever a
    row is referenced but its airing state is NOT kept fresh, which is why recommendations embed
    this rather than MediaDetail.
    """

    id: uuid.UUID


class MediaDetail(PersistedMedia):
    """A persisted title plus its airing state.

    Only correct where something refreshes that state. The sync job's worklist is scoped to titles
    that are in at least one user's library, so those fields are current for a library entry and
    for a title the user went looking for — and would be frozen at seed time anywhere else.
    """

    status: MediaStatus
    next_episode_season: int | None
    next_episode_number: int | None
    next_episode_date: datetime | None
    days_until_next_episode: int | None


class MediaSearchResponse(BaseModel):
    """Page-based, not cursor-based — the one documented exception to this API's pagination.
    Merging two independently-paginated upstreams gives no stable total order to cursor over
    without materialising both result sets first.
    """

    items: list[MediaSummary]
    page: int
    # True if any provider that ANSWERED reports more. A provider that timed out or errored
    # contributes nothing here, so `has_more: false` alongside a non-ok entry in `sources` means
    # "no more from the providers that answered", not "no more results exist". Clients deciding
    # whether to retry should read `sources`, which is why that field is not merely diagnostic.
    has_more: bool
    sources: dict[MediaSource, SourceStatus]
