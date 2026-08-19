from datetime import UTC, datetime, timedelta

import httpx
import pytest

from app.media.providers.errors import ProviderRateLimited, ProviderTimeout, ProviderUnavailable
from app.media.providers.http import ProviderHTTPClient, RateLimiter

REMAINING = "X-RateLimit-Remaining"
RESET = "X-RateLimit-Reset"


def build_client(handler) -> ProviderHTTPClient:
    transport = httpx.MockTransport(handler)
    return ProviderHTTPClient(
        httpx.AsyncClient(transport=transport, base_url="https://example.test"),
        RateLimiter(REMAINING, RESET),
    )


async def test_timeout_becomes_provider_timeout():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    with pytest.raises(ProviderTimeout):
        await build_client(handler).request("GET", "/thing")


async def test_server_error_becomes_provider_unavailable():
    client = build_client(lambda request: httpx.Response(503))
    with pytest.raises(ProviderUnavailable):
        await client.request("GET", "/thing")


async def test_429_raises_with_retry_after_seconds():
    client = build_client(lambda request: httpx.Response(429, headers={"Retry-After": "42"}))
    with pytest.raises(ProviderRateLimited) as excinfo:
        await client.request("GET", "/thing")
    assert excinfo.value.retry_after == 42.0


async def test_404_is_returned_not_raised():
    """A missing title is an ordinary answer; the provider decides what it means."""
    client = build_client(lambda request: httpx.Response(404))
    response = await client.request("GET", "/thing")
    assert response.status_code == 404


async def test_exhausted_budget_short_circuits_without_a_request():
    """Once remaining hits zero, the next call must not reach the network at all."""
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        reset = int((datetime.now(tz=UTC) + timedelta(seconds=30)).timestamp())
        return httpx.Response(200, headers={REMAINING: "0", RESET: str(reset)})

    client = build_client(handler)
    await client.request("GET", "/thing")
    assert calls == 1

    with pytest.raises(ProviderRateLimited):
        await client.request("GET", "/thing")
    assert calls == 1, "a short-circuited call must not issue an HTTP request"


async def test_absent_rate_limit_headers_do_not_disable_the_provider():
    """TMDB does not publish these headers. Treating a missing header as zero remaining would
    disable TMDB permanently after its first successful response.
    """
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    client = build_client(handler)
    for _ in range(3):
        await client.request("GET", "/thing")
    assert calls == 3
