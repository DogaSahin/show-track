import asyncio
from datetime import UTC, datetime, timedelta

import httpx
import pytest

from app.media.providers import http as http_module
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


async def test_401_becomes_provider_unavailable_even_with_a_valid_json_body():
    """TMDB's invalid-key response is a 401 carrying well-formed JSON
    ({"success": false, "status_code": 7, ...}) — a parse-only guard would never catch it. This
    must be a status check, not a JSON-decode check.
    """
    client = build_client(lambda request: httpx.Response(401, json={"success": False, "status_code": 7}))
    with pytest.raises(ProviderUnavailable):
        await client.request("GET", "/thing")


async def test_403_becomes_provider_unavailable():
    client = build_client(lambda request: httpx.Response(403, text="<html>blocked</html>"))
    with pytest.raises(ProviderUnavailable):
        await client.request("GET", "/thing")


async def test_429_raises_with_retry_after_seconds():
    client = build_client(lambda request: httpx.Response(429, headers={"Retry-After": "42"}))
    with pytest.raises(ProviderRateLimited) as excinfo:
        await client.request("GET", "/thing")
    assert excinfo.value.retry_after == 42.0


@pytest.mark.parametrize("raw", ["nan", "inf", "-inf"], ids=["nan", "inf", "negative-inf"])
async def test_a_non_finite_retry_after_reads_as_unknown(raw: str):
    """`float("nan")` and `float("inf")` parse without raising. Only logged today, but Phase 5
    will sleep on this value: NaN makes every comparison false and inf sleeps forever.
    """
    client = build_client(lambda request: httpx.Response(429, headers={"Retry-After": raw}))
    with pytest.raises(ProviderRateLimited) as excinfo:
        await client.request("GET", "/thing")
    assert excinfo.value.retry_after is None


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


async def test_out_of_range_reset_header_does_not_raise():
    """A reset header that IS a valid integer but out of datetime's representable range must
    fail open to UNKNOWN, not escape as an untyped ValueError/OverflowError past every caller's
    `except ProviderError`.
    """
    client = build_client(lambda request: httpx.Response(200, headers={REMAINING: "5", RESET: "99999999999999"}))
    response = await client.request("GET", "/thing")
    assert response.status_code == 200


async def test_remaining_zero_without_reset_header_still_short_circuits():
    """A missing reset header alongside remaining: 0 must not disable the limiter forever —
    it must fall back to a bounded window instead of never firing check() again.
    """
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, headers={REMAINING: "0"})

    client = build_client(handler)
    await client.request("GET", "/thing")
    assert calls == 1

    with pytest.raises(ProviderRateLimited):
        await client.request("GET", "/thing")
    assert calls == 1


async def test_remaining_zero_with_delta_style_reset_still_short_circuits():
    """A reset header expressed as delta-seconds (rather than absolute epoch) parses to an
    epoch timestamp in 1970 — always in the past — which would otherwise leave `now >=
    self._reset_at` permanently true and the limiter permanently dead.
    """
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, headers={REMAINING: "0", RESET: "60"})

    client = build_client(handler)
    await client.request("GET", "/thing")
    assert calls == 1

    with pytest.raises(ProviderRateLimited):
        await client.request("GET", "/thing")
    assert calls == 1


async def test_total_timeout_ceiling_becomes_provider_timeout(monkeypatch):
    """httpx's read timeout bounds a single read operation, not the request as a whole, and
    follow_redirects=True can compound it across hops. The wall-clock ceiling is what actually
    bounds a slow-trickle upstream, and it must map to the same typed exception.
    """
    monkeypatch.setattr(http_module, "TOTAL_TIMEOUT_SECONDS", 0.05)

    async def handler(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.2)
        return httpx.Response(200)

    client = build_client(handler)
    with pytest.raises(ProviderTimeout):
        await client.request("GET", "/thing")
