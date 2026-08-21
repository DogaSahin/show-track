import json
import uuid

import httpx
import pytest

from app.notifications.models import NotificationThreshold
from app.notifications.ntfy import NtfyTransport
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


async def test_a_redirect_is_retryable():
    """httpx would otherwise follow a 3xx, turning the POST into a GET and dropping the JSON
    body — ntfy answers 200 to the empty GET and the push is lost with no error anywhere. With
    follow_redirects=False on this call the 3xx reaches classification and must retry, not
    silently succeed.
    """
    with pytest.raises(TransportRetryable):
        await _transport(lambda request: httpx.Response(302, headers={"Location": "https://ntfy.test/"})).send(
            "t", MESSAGE
        )


async def test_a_connection_failure_is_retryable():
    """Fail open: a bug that loses notifications forever is worse than one that retries a few
    times and gives up. Same direction as _parse_retry_after in the provider stack.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

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
