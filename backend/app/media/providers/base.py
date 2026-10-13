import logging
from abc import ABC, abstractmethod
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import ClassVar, Protocol, runtime_checkable

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.errors import ProviderError, ProviderRateLimited

logger = logging.getLogger(__name__)


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
class ProviderEpisode:
    """One episode as a provider lists it. Specials (TMDB season 0) never appear here."""

    season_number: int
    number: int
    # None when the provider has no title (AniList never does).
    title: str | None
    # Day granularity: TMDB gives a date, and an AniList airing timestamp is cut to its UTC date.
    air_date: date | None


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


# One page from each provider: AniList's Page caps at 25 and TMDB's /recommendations returns 20.
# Deep pagination is not worth it — a title's 40th-most-similar neighbour is noise, and the cap is
# what bounds the seed job's cost at one request per seed.
SIMILAR_LIMIT = 20


# After this many failures in a row, a provider is treated as down for the rest of the batch: an
# outage then costs a few timeouts rather than one per title. A success resets the run.
MAX_CONSECUTIVE_FAILURES = 3


@dataclass(frozen=True)
class ProviderBatch:
    """The answer to get_many. An id in `found` was fetched. An id in `failed` got no usable
    answer (transport, upstream, rate limit, outage) and should be retried later. An id in
    neither is one the provider doesn't know — or whose payload could not be mapped, which is
    treated the same way so one broken title can't be re-fetched every cycle forever.
    """

    found: Mapping[str, ProviderMedia]
    failed: frozenset[str]


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

    @abstractmethod
    async def get_episodes(self, external_id: str) -> tuple[ProviderEpisode, ...] | None:
        """Every regular episode of a title, in no particular order; None when the provider has
        no such title. Raises ProviderError subclasses otherwise, never returns a partial list:
        a caller replaces what it stored with this answer, so a half list would delete episodes.
        """

    @abstractmethod
    async def fetch_similar(self, external_id: str) -> Sequence[MediaRef]:
        """Titles this provider considers similar to `external_id`, most similar first.

        ORDERED, never scored. AniList reports net upvote counts and TMDB reports nothing
        comparable, so position is the only signal both express identically — the caller fuses
        ranks, not scores (decision 7-E). At most SIMILAR_LIMIT refs.

        Returns refs rather than records (decision 7-G): TMDB's recommendations payload carries no
        `status`, and Media.status is NOT NULL, so building ProviderMedia here would mean either a
        detail call per candidate or an invented status. The caller resolves refs through
        get_many() instead, which AniList already batches.

        Empty when the provider knows the title but suggests nothing, and when the title itself is
        unknown. Raises ProviderError subclasses for transport and upstream failures.

        ABSTRACT, not a defaulted no-op: both providers support this, so the ABC is the right home
        (the same rule get_many's docstring states). A concrete `return ()` would let a provider
        silently contribute nothing to the candidate pool.
        """

    async def get_many(self, external_ids: Sequence[str]) -> ProviderBatch:
        """Fetch many titles at once, as a ProviderBatch.

        An id in `found` was fetched, keyed by external_id. An id in `failed` got no usable answer
        and should be retried later. An id in NEITHER is one the provider has no such title for —
        the same meaning get_by_id gives to None — or whose payload could not be mapped. Upstream
        failures are REPORTED in `failed`, not raised: one bad title must not cost the others in
        the same call their answer.

        A rate limit fails the id that hit it and every id not yet asked for (asking again now
        would only be refused). MAX_CONSECUTIVE_FAILURES other failures in a row fail the rest, so
        an outage costs a few timeouts rather than one per title.

        `found` is a Mapping, not a list, because a provider may answer in any order: AniList's
        batch endpoint returns results id-ordered rather than request-ordered (verified against the
        live API), so positional pairing would silently attach every title's data to the wrong
        title.

        CONCRETE, not abstract. This default is correct for TMDB, whose REST API has no batch
        endpoint, so a looping implementation is the honest one rather than a stub. AniList
        overrides it with a single GraphQL query per batch. Batching is a performance variant of
        an operation every provider supports, which is why it lives here rather than in a
        capability Protocol like UserListProvider — that models something only one provider can do
        at all.
        """
        found: dict[str, ProviderMedia] = {}
        failed: set[str] = set()
        consecutive = 0
        for index, external_id in enumerate(external_ids):
            try:
                detail = await self.get_by_id(external_id)
            except ProviderRateLimited:
                logger.warning(
                    "%s rate limited; %d titles left for the next cycle", self.source, len(external_ids) - index
                )
                failed.update(external_ids[index:])
                break
            except ProviderError:
                logger.warning("%s failed for %s", self.source, external_id, exc_info=True)
                failed.add(external_id)
                consecutive += 1
                if consecutive >= MAX_CONSECUTIVE_FAILURES:
                    failed.update(external_ids[index + 1 :])
                    break
                continue
            except (KeyError, TypeError, ValueError, AttributeError):
                # A payload the mapper can't read. Logged with its traceback so a genuine bug is
                # visible, and treated as "unknown" so it is stamped rather than retried hourly.
                logger.exception("unmappable %s payload for %s; skipping it", self.source, external_id)
                consecutive = 0
                continue
            consecutive = 0
            if detail is not None:
                found[external_id] = detail
        return ProviderBatch(found=found, failed=frozenset(failed))


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
