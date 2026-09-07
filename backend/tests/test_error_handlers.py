import json

from starlette.requests import Request

from app.errors import HANDLED
from app.media.providers.errors import ProviderError, ProviderRateLimited, ProviderUnavailable
from main import app


def _request() -> Request:
    """The minimum ASGI scope a handler needs. Handlers here read nothing off the request, but
    the signature Starlette calls them with requires one.
    """
    return Request({"type": "http", "method": "GET", "path": "/", "headers": []})


def test_every_handled_exception_is_registered_on_the_app():
    """Dropping a registration turns a 502 into a 500 with nothing failing — the same silent
    regression class as the auth-mount invariant, so it gets the same kind of guard.
    """
    for exc_type in HANDLED:
        assert exc_type in app.exception_handlers, f"{exc_type.__name__} has no registered handler"


async def test_a_rate_limited_error_carries_a_ceiled_retry_after_header():
    """Closes the Phase 3 follow-up "ProviderRateLimited.retry_after never reaches the client".
    Retry-After is an integer number of seconds, so a 1.2s wait must round UP — rounding down
    tells the client to retry while still rate limited.
    """
    handler = app.exception_handlers[ProviderRateLimited]

    response = await handler(_request(), ProviderRateLimited(retry_after=1.2))

    assert response.status_code == 429
    assert response.headers["Retry-After"] == "2"


async def test_a_rate_limited_error_without_a_retry_after_sends_no_header():
    """Unknown must stay unknown. A fabricated Retry-After is worse than none."""
    handler = app.exception_handlers[ProviderRateLimited]

    response = await handler(_request(), ProviderRateLimited(retry_after=None))

    assert "Retry-After" not in response.headers


async def test_the_detail_ignores_the_exception_message_entirely():
    """The response body is the table's fixed string, whatever the exception says.

    Provider messages carry internal state — upstream URLs, transport error text — that is not
    part of this API's contract. Asserting the handler ignores the message (rather than
    asserting one particular secret is absent) is what makes this test still meaningful after
    someone changes how those messages are built.
    """
    handler = app.exception_handlers[ProviderError]
    exc = ProviderUnavailable("GET https://internal.example/v3/x?token=MARKER returned 500")

    response = await handler(_request(), exc)
    body = json.loads(response.body)

    assert response.status_code == 502
    assert body == {"detail": HANDLED[ProviderError][1]}
    assert "MARKER" not in response.body.decode()
