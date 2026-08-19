from enum import StrEnum

from pydantic import BaseModel

from app.media.models import MediaSource, MediaType


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


class MediaSearchResponse(BaseModel):
    """Page-based, not cursor-based — the one documented exception to this API's pagination.
    Merging two independently-paginated upstreams gives no stable total order to cursor over
    without materialising both result sets first.
    """

    items: list[MediaSummary]
    page: int
    has_more: bool
    sources: dict[MediaSource, SourceStatus]
