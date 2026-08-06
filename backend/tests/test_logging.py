import json
import logging
import sys

from httpx import ASGITransport, AsyncClient

from app.logging import JsonFormatter, RequestIDFilter, request_id_var, setup_logging


def _make_record(level: int = logging.INFO, msg: str = "hello") -> logging.LogRecord:
    return logging.LogRecord(
        name="showtrack.test",
        level=level,
        pathname=__file__,
        lineno=1,
        msg=msg,
        args=(),
        exc_info=None,
    )


def test_filter_stamps_the_current_request_id() -> None:
    record = _make_record()
    token = request_id_var.set("abc-123")
    try:
        assert RequestIDFilter().filter(record) is True
    finally:
        request_id_var.reset(token)

    assert record.request_id == "abc-123"


def test_filter_stamps_a_placeholder_outside_a_request() -> None:
    record = _make_record()

    RequestIDFilter().filter(record)

    assert record.request_id == "-"


def test_formatter_emits_valid_json() -> None:
    record = _make_record()
    RequestIDFilter().filter(record)

    payload = json.loads(JsonFormatter().format(record))

    assert payload["level"] == "INFO"
    assert payload["logger"] == "showtrack.test"
    assert payload["message"] == "hello"
    assert payload["request_id"] == "-"


def test_formatter_promotes_extra_fields_to_top_level() -> None:
    record = _make_record()
    record.status = 404
    record.path = "/v1/library"

    payload = json.loads(JsonFormatter().format(record))

    assert payload["status"] == 404
    assert payload["path"] == "/v1/library"


def test_formatter_includes_exception_text() -> None:
    try:
        raise ValueError("boom")
    except ValueError:
        record = _make_record(level=logging.ERROR, msg="failed")
        record.exc_info = sys.exc_info()

    payload = json.loads(JsonFormatter().format(record))

    assert "ValueError: boom" in payload["exception"]


async def test_response_carries_request_id_header(client: AsyncClient) -> None:
    response = await client.get("/health")

    assert response.headers["x-request-id"]


async def test_supplied_request_id_is_echoed(client: AsyncClient) -> None:
    response = await client.get("/health", headers={"X-Request-ID": "caller-supplied"})

    assert response.headers["x-request-id"] == "caller-supplied"


async def test_each_request_gets_a_distinct_id(client: AsyncClient) -> None:
    first = await client.get("/health")
    second = await client.get("/health")

    assert first.headers["x-request-id"] != second.headers["x-request-id"]


async def test_context_var_is_reset_after_the_request(client: AsyncClient) -> None:
    """A leaked ID would attach the wrong correlation to later background work."""
    await client.get("/health", headers={"X-Request-ID": "leaky"})

    assert request_id_var.get() == "-"


async def test_filter_stamps_id_that_survives_deferred_formatting(client: AsyncClient) -> None:
    """The central design invariant: the record must carry the correct request ID even
    when it is formatted *after* the request has ended and the contextvar has been reset.

    This models a deferred handler (a QueueHandler, or pytest's own caplog): the record
    is captured during the request (contextvar still live) but only turned into JSON
    afterwards. If RequestIDFilter were dropped, or JsonFormatter read
    ``request_id_var`` directly instead of ``record.request_id``, this would read "-"
    instead of the real ID, because by format time the middleware's `finally` has
    already reset the contextvar.
    """
    captured: list[logging.LogRecord] = []

    class _CaptureHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured.append(record)

    handler = _CaptureHandler()
    handler.addFilter(RequestIDFilter())
    access_logger = logging.getLogger("showtrack.access")
    access_logger.addHandler(handler)
    try:
        response = await client.get("/health")
    finally:
        access_logger.removeHandler(handler)

    # The request has finished: the contextvar is back to the placeholder, proving that
    # any formatting done from here on cannot recover the ID by reading it directly.
    assert request_id_var.get() == "-"
    assert captured, "expected the access logger to emit a record during the request"

    payload = json.loads(JsonFormatter().format(captured[0]))

    assert payload["request_id"] == response.headers["x-request-id"]
    assert payload["request_id"] != "-"


def test_setup_logging_attaches_a_request_id_filter_to_the_root_handler() -> None:
    """Guards the wiring itself: it is not enough for RequestIDFilter to work correctly
    in isolation if setup_logging() forgets to attach it to the real handler.
    """
    root = logging.getLogger()
    original_handlers = list(root.handlers)
    original_level = root.level
    try:
        setup_logging()

        assert root.handlers, "setup_logging() should install at least one handler"
        assert any(isinstance(f, RequestIDFilter) for h in root.handlers for f in h.filters), (
            "expected a RequestIDFilter attached to a root handler"
        )
    finally:
        root.handlers.clear()
        root.handlers.extend(original_handlers)
        root.setLevel(original_level)


async def test_unhandled_exception_is_logged_with_request_id_and_traceback() -> None:
    """Finding 3(a): a raised exception must still produce a correlated ERROR log line
    through our JSON logger, not silence, and not just uvicorn's plain-text output.
    """
    from main import app as fastapi_app

    async def _boom() -> None:
        raise RuntimeError("kaboom")

    fastapi_app.add_api_route("/__test/boom", _boom, methods=["GET"])
    route = fastapi_app.routes[-1]

    captured: list[logging.LogRecord] = []

    class _CaptureHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured.append(record)

    handler = _CaptureHandler()
    access_logger = logging.getLogger("showtrack.access")
    access_logger.addHandler(handler)

    transport = ASGITransport(app=fastapi_app, raise_app_exceptions=False)
    try:
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            response = await ac.get("/__test/boom", headers={"X-Request-ID": "boom-id"})
    finally:
        access_logger.removeHandler(handler)
        fastapi_app.routes.remove(route)

    assert response.status_code == 500

    error_records = [r for r in captured if r.levelno == logging.ERROR]
    assert error_records, "expected an ERROR record on showtrack.access"
    assert error_records[0].request_id == "boom-id"
    assert error_records[0].exc_info is not None

    payload = json.loads(JsonFormatter().format(error_records[0]))
    assert "RuntimeError: kaboom" in payload["exception"]


async def test_unhandled_exception_response_still_carries_request_id_header() -> None:
    """Finding 3(b): the 500 response the client actually receives must carry
    X-Request-ID too, even though the contextvar has already been reset by the time
    FastAPI's exception handler runs.
    """
    from main import app as fastapi_app

    async def _boom() -> None:
        raise RuntimeError("kaboom")

    fastapi_app.add_api_route("/__test/boom-header", _boom, methods=["GET"])
    route = fastapi_app.routes[-1]

    transport = ASGITransport(app=fastapi_app, raise_app_exceptions=False)
    try:
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            response = await ac.get("/__test/boom-header", headers={"X-Request-ID": "boom-header-id"})
    finally:
        fastapi_app.routes.remove(route)

    assert response.status_code == 500
    assert response.headers["x-request-id"] == "boom-header-id"
