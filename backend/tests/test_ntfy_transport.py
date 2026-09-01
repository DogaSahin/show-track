import asyncio
import json
import uuid

import httpx
import pytest

from app.config import Settings
from app.notifications import ntfy as ntfy_module
from app.notifications.models import NotificationThreshold
from app.notifications.ntfy import NtfyTransport, get_transport
from app.notifications.transport import PushMessage, TransportPermanent, TransportRetryable

MESSAGE = PushMessage(
    title="Frieren",
    body="Episode 12 airs in 24 hours",
    media_id=uuid.uuid4(),
    episode_number=12,
    threshold=NotificationThreshold.TWENTY_FOUR_HOURS,
)


def _transport(handler) -> NtfyTransport:
    """An NtfyTransport wired to an in-memory httpx transport. No socket is opened — the suite
    must never touch a real ntfy server (CLAUDE.md guardrails).
    """
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return NtfyTransport(base_url="http://ntfy.test", token=None, client=client)


async def test_a_successful_publish_posts_the_topic_and_body():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["json"] = json.loads(request.content)
        return httpx.Response(200)

    await _transport(handler).send("secret-topic", MESSAGE)

    assert seen["json"]["topic"] == "secret-topic"
    assert seen["json"]["title"] == "Frieren"
    assert seen["json"]["message"] == "Episode 12 airs in 24 hours"


async def test_a_server_error_is_retryable():
    """5xx is transient. Retrying is the whole point of the attempts counter."""
    with pytest.raises(TransportRetryable):
        await _transport(lambda request: httpx.Response(503)).send("t", MESSAGE)


async def test_a_rate_limit_is_retryable_despite_being_4xx():
    """429 is the one 4xx that WILL succeed later. Classifying it with the other 4xx would
    delete the target row over a temporary condition.
    """
    with pytest.raises(TransportRetryable):
        await _transport(lambda request: httpx.Response(429)).send("t", MESSAGE)


async def test_an_unknown_topic_is_permanent():
    """A 404 will never succeed. It must prune the target rather than retry forever."""
    with pytest.raises(TransportPermanent):
        await _transport(lambda request: httpx.Response(404)).send("t", MESSAGE)


async def test_a_followed_redirect_would_be_a_silent_success():
    """The regression this pins: with follow_redirects on, httpx converts POST to GET and DROPS
    the JSON body, ntfy's web UI answers 200, is_success returns, the task is marked sent, and
    the push is lost with no error anywhere. NTFY_BASE_URL=http:// behind a TLS-terminating
    proxy is the likeliest real misconfiguration and it fails invisibly.

    The client below deliberately sets follow_redirects=True to MATCH app/http.py — the process
    client the transport uses in production. A MockTransport client built via `_transport()`
    defaults follow_redirects to False, so a test built on that default would pass even with the
    fix in send() removed — which is exactly what the previous version of this test did.

    Hop 2 answers 200 rather than another 302: that is what makes the assertion meaningful. If
    the redirect were followed, send() would return normally and this test would fail.
    """
    hops = []

    def handler(request: httpx.Request) -> httpx.Response:
        hops.append(request.url)
        if len(hops) == 1:
            return httpx.Response(302, headers={"Location": "http://ntfy.test/redirected"})
        return httpx.Response(200)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=True)
    transport = NtfyTransport(base_url="http://ntfy.test", token=None, client=client)

    with pytest.raises(TransportRetryable):
        await transport.send("t", MESSAGE)

    assert len(hops) == 1, "the transport must not follow the redirect"


async def test_a_connection_failure_is_retryable():
    """Fail open: a bug that loses notifications forever is worse than one that retries a few
    times and gives up. Same direction as _parse_retry_after in the provider stack.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    with pytest.raises(TransportRetryable):
        await _transport(handler).send("t", MESSAGE)


async def test_a_slow_trickling_server_hits_the_wall_clock_ceiling(monkeypatch):
    """The shared client's timeout is PER READ, not a total, so a server that dribbles bytes
    resets it indefinitely and send() never returns. run_dispatch would then never return either,
    holding the dispatch advisory lock forever while APScheduler logs "maximum number of running
    instances reached" every minute and notifications stop dead.

    The ceiling is monkeypatched down rather than slept through: this must cost milliseconds, and
    the assertion is about the branch existing, not about the constant's value.

    asyncio.timeout raises the BUILTIN TimeoutError, which is not an httpx.HTTPError — so without
    its own except clause it escapes send() entirely rather than becoming TransportRetryable.
    """
    monkeypatch.setattr(ntfy_module, "TOTAL_TIMEOUT_SECONDS", 0.05)

    async def handler(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(5)
        return httpx.Response(200)

    with pytest.raises(TransportRetryable):
        await _transport(handler).send("t", MESSAGE)


async def test_the_auth_token_never_appears_in_an_error():
    """NTFY_TOKEN is a credential. app/errors.py pins response details to fixed strings for the
    same reason; this checks the exception the transport itself raises.
    """
    transport = NtfyTransport(
        base_url="http://ntfy.test",
        token="tk_supersecret",
        client=httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(500))),
    )
    with pytest.raises(TransportRetryable) as caught:
        await transport.send("t", MESSAGE)

    assert "tk_supersecret" not in str(caught.value)
    # This drives a 500 status response, which raises from the plain status-code branch with no
    # `from exc` — so __cause__ is None here and this assertion is vacuously true. It pins that
    # fact rather than testing chaining; test_neither_secret_leaks_through_a_chained_transport_error
    # below covers the branch that actually chains an exception onto __cause__.
    assert caught.value.__cause__ is None


async def test_the_topic_never_appears_in_an_error():
    """The topic is a bearer secret (6-L). An exception string ends up in a log line."""
    with pytest.raises(TransportRetryable) as caught:
        await _transport(lambda request: httpx.Response(500)).send("very-secret-topic", MESSAGE)

    assert "very-secret-topic" not in str(caught.value)
    # Same reasoning as the auth-token test above: a 500 status response raises bare, with no
    # `from exc`, so __cause__ is None here. Pinned rather than asserted-against for the same
    # reason — the chained branch is covered separately below.
    assert caught.value.__cause__ is None


async def test_neither_secret_leaks_through_a_chained_transport_error():
    """The status-code branch raises bare; THIS branch raises `from exc`, so httpx's own
    exception rides along as __cause__ — and __cause__ is what logger.exception prints.

    Separate from the two status-code tests on purpose: those cannot exercise this, because a
    plain `raise` leaves __cause__ as None and any assertion about it passes vacuously.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    transport = NtfyTransport(
        base_url="http://ntfy.test",
        token="tk_supersecret",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(TransportRetryable) as caught:
        await transport.send("very-secret-topic", MESSAGE)

    rendered = f"{caught.value}{caught.value.__cause__!r}"
    assert "tk_supersecret" not in rendered
    assert "very-secret-topic" not in rendered


def _settings(**overrides) -> Settings:
    """Settings built without reading the developer's real .env.

    Same convention and reason as tests/test_scheduler.py's helper of the same name: without
    `_env_file=None` these assertions would read whatever NTFY_BASE_URL happens to be set in the
    developer's environment rather than the value passed here.
    """
    base = {
        "_env_file": None,
        "database_url": "postgresql+asyncpg://x/y",
        "secret_key": "x",
        "registration_code": "x",
    }
    return Settings(**{**base, **overrides})


def test_get_transport_returns_none_when_ntfy_base_url_is_unset(monkeypatch):
    """Absent config is the documented way ntfy stays optional (6-K): no transport, no
    dispatch job, tasks simply queue as pending.
    """
    monkeypatch.setattr(ntfy_module, "get_settings", lambda: _settings(ntfy_base_url=None))

    assert get_transport() is None


def test_get_transport_returns_none_for_a_scheme_less_base_url(monkeypatch):
    """A scheme-less NTFY_BASE_URL (e.g. copy-pasted without "http://") would otherwise make
    httpx raise InvalidURL from inside send() — which does not subclass HTTPError, so it would
    escape uncaught and abort the dispatcher's send loop mid-batch. Disabling here instead keeps
    that failure mode out of send() entirely.
    """
    monkeypatch.setattr(ntfy_module, "get_settings", lambda: _settings(ntfy_base_url="ntfy.example.com"))

    assert get_transport() is None
