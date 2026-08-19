import logging
from collections.abc import Mapping

from app.config import Settings, get_settings
from app.media.models import MediaSource
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.base import MediaProvider
from app.media.providers.http import ProviderHTTPClient, RateLimiter, get_http_client
from app.media.providers.tmdb.client import TMDBProvider

logger = logging.getLogger(__name__)

_REMAINING_HEADER = "X-RateLimit-Remaining"
_RESET_HEADER = "X-RateLimit-Reset"

_registry: dict[MediaSource, MediaProvider] | None = None


def build_registry(settings: Settings) -> dict[MediaSource, MediaProvider]:
    """One line per provider. Adding a third means a MediaSource value, a migration for the
    CHECK constraint on media.source, a provider package, a genre table, config, fixtures — and
    this entry. Six of those seven are irreducible; this is the seventh.

    Every provider shares the process-wide httpx client (one connection pool) but gets its OWN
    RateLimiter: limiter state is per-upstream, and a shared instance would let AniList's
    exhausted budget throttle TMDB.
    """
    client = get_http_client()
    providers: dict[MediaSource, MediaProvider] = {
        MediaSource.ANILIST: AniListProvider(ProviderHTTPClient(client, RateLimiter(_REMAINING_HEADER, _RESET_HEADER))),
    }

    if settings.tmdb_api_key:
        providers[MediaSource.TMDB] = TMDBProvider(
            ProviderHTTPClient(client, RateLimiter(_REMAINING_HEADER, _RESET_HEADER)),
            api_key=settings.tmdb_api_key,
        )
    else:
        logger.warning("TMDB_API_KEY is not set; media search will return AniList results only")

    return providers


def get_providers() -> Mapping[MediaSource, MediaProvider]:
    """A lazy accessor, not a module-level constant, for the reason app/db.py builds its engine
    lazily: a constant is built at import time, which would construct an httpx.AsyncClient
    before the event loop exists.

    Used as a FastAPI dependency so route tests override it through app.dependency_overrides —
    the same mechanism conftest.py already uses for get_session — rather than monkeypatching a
    module global.
    """
    global _registry
    if _registry is None:
        _registry = build_registry(get_settings())
    return _registry


def reset_providers() -> None:
    """Clear the memo so the next get_providers() rebuilds."""
    global _registry
    _registry = None
