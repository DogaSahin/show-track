import json
import logging
import sys

from httpx import AsyncClient

from app.logging import JsonFormatter, RequestIDFilter, request_id_var


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
