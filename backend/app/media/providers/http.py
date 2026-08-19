import logging
from datetime import UTC, datetime
from typing import Any

import httpx

from app.media.providers.errors import ProviderRateLimited, ProviderTimeout, ProviderUnavailable

logger = logging.getLogger(__name__)

# Module constants, not settings: these are code-level policy, and promoting them to config
# would add an .env surface to document for something nobody will tune.
CONNECT_TIMEOUT_SECONDS = 3.0
READ_TIMEOUT_SECONDS = 5.0

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
    """

    def __init__(self, remaining_header: str, reset_header: str) -> None:
        self._remaining_header = remaining_header
        self._reset_header = reset_header
        self._remaining: int | None = None
        self._reset_at: datetime | None = None

    def check(self, now: datetime) -> None:
        if self._remaining != 0 or self._reset_at is None or now >= self._reset_at:
            return
        raise ProviderRateLimited(retry_after=(self._reset_at - now).total_seconds())

    def observe(self, response: httpx.Response, now: datetime) -> None:
        remaining = _read_int(response.headers.get(self._remaining_header))
        if remaining is None:
            # Absent means UNKNOWN, never exhausted. TMDB publishes no such header, and
            # treating None as 0 would disable it permanently after one response.
            return
        self._remaining = remaining
        reset = _read_int(response.headers.get(self._reset_header))
        self._reset_at = datetime.fromtimestamp(reset, tz=UTC) if reset is not None else None


def _read_int(raw: str | None) -> int | None:
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
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
            response = await self._client.request(method, url, **kwargs)
        except httpx.TimeoutException as exc:
            raise ProviderTimeout(f"{method} {url} timed out") from exc
        except httpx.HTTPError as exc:
            raise ProviderUnavailable(f"{method} {url} failed: {exc}") from exc

        self._limiter.observe(response, datetime.now(tz=UTC))

        if response.status_code == 429:
            raise ProviderRateLimited(retry_after=_parse_retry_after(response))
        if response.status_code >= 500:
            raise ProviderUnavailable(f"{method} {url} returned {response.status_code}")
        return response
