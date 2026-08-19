import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx

from app.media.providers.errors import ProviderRateLimited, ProviderTimeout, ProviderUnavailable

logger = logging.getLogger(__name__)

# Module constants, not settings: these are code-level policy, and promoting them to config
# would add an .env surface to document for something nobody will tune.
CONNECT_TIMEOUT_SECONDS = 3.0
READ_TIMEOUT_SECONDS = 5.0
# Wall-clock ceiling on a single request. httpx's read timeout is per read operation, not a
# total, and follow_redirects=True can compound it across up to 20 hops — a slow-trickle
# upstream could otherwise hold a request open indefinitely. Sits above READ_TIMEOUT_SECONDS
# and below the 6s guard the search service adds on top (Task 3.7), so search stays snappy
# while background callers still get a hard ceiling.
TOTAL_TIMEOUT_SECONDS = 8.0
# Fallback window used when a rate limiter observes remaining == 0 but cannot trust the
# provider's reset time (missing, unparseable, out-of-range, or a delta-seconds header
# misread as an epoch timestamp already in the past). Fail-open stays the direction — we would
# rather retry too soon than disable a provider forever — but bounded rather than unbounded.
FALLBACK_RESET_WINDOW_SECONDS = 60.0

_client: httpx.AsyncClient | None = None


def get_http_client() -> httpx.AsyncClient:
    """One client per process, built on first use.

    Not at import time, for the same reason app/db.py builds its engine lazily: the client
    binds to the running event loop. Not per request either — a fresh client discards
    connection pooling and TLS session reuse, and search makes two outbound calls every time.
    """
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(READ_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS),
            follow_redirects=True,
        )
    return _client


async def close_http_client() -> None:
    """Release the connection pool and clear the memo, so a later get_http_client() rebuilds."""
    global _client
    if _client is not None:
        await _client.aclose()
    _client = None


def _parse_retry_after(response: httpx.Response) -> float | None:
    """Retry-After is either a number of seconds or an HTTP-date. We read the numeric form and
    return None for the date form rather than parsing it — no provider we use sends dates, and
    an unknown retry_after is honest where a wrong one is not.
    """
    raw = response.headers.get("Retry-After")
    if raw is None:
        return None
    try:
        return float(raw)
    except ValueError:
        return None


class RateLimiter:
    """Reactive: it learns the budget from response headers instead of assuming a documented one.

    AniList publishes 90 requests/minute but has run degraded at 30/min for long stretches, so a
    limiter built on the documented number breaks silently exactly when the upstream is already
    struggling.

    `now` is a parameter rather than read internally so the behaviour is testable without
    freezing the clock.

    Not atomic: `check()` -> caller's request -> `observe()` is three separate steps, so N
    coroutines racing on `remaining: 1` can all pass `check()` before any of their `observe()`
    calls land, overshooting the budget by the concurrency degree. That is acceptable for a
    reactive limiter — it stops the network calls that come *after* zero is observed, and was
    never meant to prevent the first burst — but it is a stated property, not an accident.
    Phase 5's sync job is where fan-out will actually exercise it.
    """

    def __init__(self, remaining_header: str, reset_header: str) -> None:
        self._remaining_header = remaining_header
        self._reset_header = reset_header
        self._remaining: int | None = None
        self._reset_at: datetime | None = None

    def check(self, now: datetime) -> None:
        if self._remaining != 0 or self._reset_at is None or now >= self._reset_at:
            return
        retry_after = (self._reset_at - now).total_seconds()
        logger.warning("rate limiter short-circuited a request; retry_after=%.1fs", retry_after)
        raise ProviderRateLimited(retry_after=retry_after)

    def observe(self, response: httpx.Response, now: datetime) -> None:
        remaining = _read_int(response.headers.get(self._remaining_header))
        if remaining is None:
            # Absent means UNKNOWN, never exhausted. TMDB publishes no such header, and
            # treating None as 0 would disable it permanently after one response.
            return
        self._remaining = remaining
        reset_at = _parse_reset_at(_read_int(response.headers.get(self._reset_header)))
        if remaining == 0 and (reset_at is None or reset_at <= now):
            # The reset header is missing, unparseable, out of range, or a delta-seconds value
            # misread as an epoch timestamp already in the past. Any of those would otherwise
            # turn exhaustion into a permanent no-op (check() never fires again because
            # `now >= self._reset_at` stays true forever). Fall back to a fixed window instead
            # of trusting an unusable value.
            reset_at = now + timedelta(seconds=FALLBACK_RESET_WINDOW_SECONDS)
        self._reset_at = reset_at


def _read_int(raw: str | None) -> int | None:
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def _parse_reset_at(reset: int | None) -> datetime | None:
    """Convert an epoch-seconds reset value, guarding the one place third-party bytes reach
    unguarded code: a value that IS a valid integer but out of `datetime`'s representable range
    raises ValueError, OverflowError, or OSError depending on platform. That must fail open to
    UNKNOWN, exactly like an unparseable `remaining` does, instead of escaping as an untyped
    exception that defeats this layer's one job.
    """
    if reset is None:
        return None
    try:
        return datetime.fromtimestamp(reset, tz=UTC)
    except (ValueError, OverflowError, OSError):
        return None


class ProviderHTTPClient:
    """Transport with one policy: it never retries and never sleeps.

    On a 429 it raises with retry_after attached and the CALLER decides. On the search path a
    user is waiting, so sleeping is strictly worse than reporting `rate_limited` in the
    response; in a background sync job sleeping is exactly right. Putting retry in here would
    impose one policy on both.
    """

    def __init__(self, client: httpx.AsyncClient, limiter: RateLimiter) -> None:
        self._client = client
        self._limiter = limiter

    async def request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        self._limiter.check(datetime.now(tz=UTC))
        try:
            async with asyncio.timeout(TOTAL_TIMEOUT_SECONDS):
                response = await self._client.request(method, url, **kwargs)
        except (httpx.TimeoutException, TimeoutError) as exc:
            # httpx.TimeoutException covers per-read/connect timeouts; the bare TimeoutError is
            # what asyncio.timeout raises when the wall-clock ceiling above fires instead.
            raise ProviderTimeout(f"{method} {url} timed out") from exc
        except (httpx.HTTPError, httpx.InvalidURL, httpx.CookieConflict) as exc:
            # httpx.InvalidURL and httpx.CookieConflict do NOT subclass httpx.HTTPError, so a
            # plain `except httpx.HTTPError` lets them escape untyped — the same class of leak
            # as an unguarded reset-header conversion. Nothing from httpx should reach a caller
            # unwrapped.
            raise ProviderUnavailable(f"{method} {url} failed: {exc}") from exc

        self._limiter.observe(response, datetime.now(tz=UTC))

        if response.status_code == 429:
            retry_after = _parse_retry_after(response)
            # Never log the full URL: TMDB's API key travels in the query string.
            logger.warning("provider rate limited %s; retry_after=%s", method, retry_after)
            raise ProviderRateLimited(retry_after=retry_after)
        if response.status_code >= 500:
            raise ProviderUnavailable(f"{method} {url} returned {response.status_code}")
        return response
