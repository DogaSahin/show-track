import asyncio
import logging
from collections.abc import Mapping
from dataclasses import dataclass
from itertools import zip_longest

from app.media.models import MediaSource
from app.media.providers.base import MediaProvider, ProviderMediaSummary, ProviderSearchPage
from app.media.providers.errors import ProviderError, ProviderRateLimited, ProviderTimeout
from app.media.schemas import MediaSearchResponse, MediaSummary, SourceStatus

logger = logging.getLogger(__name__)

# Above the HTTP layer's 5s read timeout on purpose: this is the outer guard for a provider
# that stalls BETWEEN requests rather than during one.
SEARCH_TIMEOUT_SECONDS = 6.0


@dataclass(frozen=True, slots=True)
class _Outcome:
    source: MediaSource
    status: SourceStatus
    page: ProviderSearchPage | None


async def _search_one(provider: MediaProvider, query: str, page: int) -> _Outcome:
    """Never raises.

    Exceptions must not reach asyncio.gather: with its default return_exceptions=False the first
    failure propagates immediately and the sibling's result is discarded, which makes partial
    results impossible. return_exceptions=True also works but hands every caller a
    `list[X | BaseException]` to type-switch over. Catching here keeps the result type honest.
    """
    try:
        async with asyncio.timeout(SEARCH_TIMEOUT_SECONDS):
            result = await provider.search(query, page)
    except (ProviderTimeout, TimeoutError):
        logger.warning("provider %s timed out during search", provider.source)
        return _Outcome(provider.source, SourceStatus.TIMEOUT, None)
    except ProviderRateLimited:
        logger.warning("provider %s is rate limited", provider.source)
        return _Outcome(provider.source, SourceStatus.RATE_LIMITED, None)
    except ProviderError:
        logger.exception("provider %s failed during search", provider.source)
        return _Outcome(provider.source, SourceStatus.ERROR, None)
    return _Outcome(provider.source, SourceStatus.OK, result)


def _interleave(pages: list[ProviderSearchPage]) -> list[ProviderMediaSummary]:
    """Round-robin across providers.

    Each provider returns its own relevance-ranked list; interleaving preserves both rankings
    internally while giving neither exclusive claim to the top slot, and needs no similarity
    metric we would have to defend. If one provider returns nothing this degenerates to plain
    concatenation at no cost.
    """
    return [item for row in zip_longest(*(page.items for page in pages)) for item in row if item is not None]


def _to_summary(item: ProviderMediaSummary) -> MediaSummary:
    return MediaSummary(
        source=item.ref.source,
        external_id=item.ref.external_id,
        type=item.type,
        title=item.title,
        year=item.year,
        genres=list(item.genres),
        cover_image_url=item.cover_image_url,
    )


async def search_media(providers: Mapping[MediaSource, MediaProvider], query: str, page: int) -> MediaSearchResponse:
    # sorted() so interleave order does not depend on dict insertion order. A stable ordering is
    # what makes the route tests assert on a sequence rather than a set.
    ordered = [providers[source] for source in sorted(providers)]
    outcomes = await asyncio.gather(*(_search_one(provider, query, page) for provider in ordered))

    sources = {source: SourceStatus.NOT_CONFIGURED for source in MediaSource}
    sources.update({outcome.source: outcome.status for outcome in outcomes})

    pages = [outcome.page for outcome in outcomes if outcome.page is not None]
    return MediaSearchResponse(
        items=[_to_summary(item) for item in _interleave(pages)],
        page=page,
        has_more=any(page_result.has_more for page_result in pages),
        sources=sources,
    )
