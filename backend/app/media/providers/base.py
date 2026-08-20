from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import ClassVar, Protocol, runtime_checkable

from app.media.models import MediaSource, MediaStatus, MediaType


@dataclass(frozen=True, slots=True)
class MediaRef:
    """Provider-scoped identity — the only way to name a title that has no database row yet.

    This one type is simultaneously the identity of a search result, the body of
    POST /v1/library, and the argument to get_or_create_media(). Without it those three grow
    independent (source, external_id) pairs that drift apart.
    """

    source: MediaSource
    external_id: str


@dataclass(frozen=True, slots=True)
class NextEpisode:
    season_number: int
    number: int
    # tz-aware. TMDB supplies a date only, so TMDB values are midnight UTC — fabricated
    # precision, documented in the spec. AniList supplies a real airing timestamp.
    airs_at: datetime | None


@dataclass(frozen=True, slots=True)
class ProviderMediaSummary:
    """What search returns. Thinner than ProviderMedia because TMDB's /search/tv physically
    cannot fill the rest: it returns no `status` and no `next_episode_to_air`.
    """

    ref: MediaRef
    type: MediaType
    title: str
    year: int | None
    genres: tuple[str, ...]  # canonical names, already mapped at the provider boundary
    cover_image_url: str | None


@dataclass(frozen=True, slots=True)
class ProviderMedia:
    """What detail returns."""

    ref: MediaRef
    type: MediaType
    title: str
    year: int | None
    genres: tuple[str, ...]
    cover_image_url: str | None
    status: MediaStatus
    next_episode: NextEpisode | None


@dataclass(frozen=True, slots=True)
class ProviderSearchPage:
    items: tuple[ProviderMediaSummary, ...]
    # Not derivable from len(items): a provider can return a full page and still be the last one.
    has_more: bool


class MediaProvider(ABC):
    """One external source of titles, normalized.

    An ABC rather than a Protocol: providers share a constructor taking the HTTP client, and a
    future batched get_many() wants a default implementation that loops with AniList overriding
    it (one GraphQL query can fetch many ids; TMDB's REST API cannot). Protocols carry no
    defaults. get_many() is not defined here — Phase 5 does not exist yet.

    There is deliberately no get_next_episode(): both upstreams return the next episode inside
    the detail payload, so a separate method would issue a second identical request per title
    per sync cycle, and requests are what a rate limit counts.
    """

    source: ClassVar[MediaSource]
    media_type: ClassVar[MediaType]

    @abstractmethod
    async def search(self, query: str, page: int) -> ProviderSearchPage:
        """Page 1-indexed. Raises ProviderError subclasses; never returns a partial page."""

    @abstractmethod
    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        """None when the provider has no such title. Raises ProviderError subclasses otherwise."""


class ListEntryStatus(StrEnum):
    """A user's relationship to a title, in provider-neutral vocabulary.

    Duplicates library.models.UserMediaStatus member for member, and that is the point: the
    alternative is `media` importing from `library`, which inverts the existing library -> media
    dependency into a cycle. The cost is five members and one mapping dict; what it buys is an
    anti-corruption layer, so a provider's vocabulary can change without the domain caring.
    """

    WATCHING = "watching"
    PLANNED = "planned"
    COMPLETED = "completed"
    DROPPED = "dropped"
    PAUSED = "paused"


@dataclass(frozen=True, slots=True)
class ProviderListEntry:
    """One row of somebody's list, normalized.

    `score` is Decimal, not float: routing it through binary floating point would reintroduce
    exactly the drift NUMERIC(3,1) exists to prevent. None means unscored, never 0.
    """

    media: ProviderMedia
    status: ListEntryStatus
    score: Decimal | None
    progress: int


@dataclass(frozen=True, slots=True)
class ProviderUserList:
    """`dropped` counts entries the provider returned that could not be mapped, so the import
    summary's `failed` is a real number rather than always zero.

    `truncated` is decision 4-L: a list longer than the chunk cap returns a prefix, and without
    this flag that outcome is byte-identical to a complete import in the API response.
    """

    entries: tuple[ProviderListEntry, ...]
    dropped: int
    truncated: bool = False


@runtime_checkable
class UserListProvider(Protocol):
    """A capability, not a provider family.

    Deliberately NOT a method on MediaProvider: TMDB has no concept of a user's list, and both
    possible defaults are wrong. Returning empty turns a wiring bug into "import succeeded, 0
    titles" — the failure this package's own comments reject for search. Raising
    NotImplementedError means the caller catches it anyway, expressed as an exception type that
    means "programmer error", which is not what happened.

    runtime_checkable verifies method PRESENCE, not signature: a smoke alarm, not a contract.
    """

    async def fetch_user_list(self, username: str) -> ProviderUserList: ...
