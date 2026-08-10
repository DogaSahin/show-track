import json
import logging
from contextvars import ContextVar

from app.config import get_settings

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

_RESERVED = frozenset(logging.LogRecord("", 0, "", 0, "", (), None).__dict__) | {
    "message",
    "asctime",
    "request_id",
    "taskName",
}


class RequestIDFilter(logging.Filter):
    """Stamps the current request ID onto the record at emit time.

    Must happen here rather than in the formatter: formatting can occur after the
    request has finished and the contextvar has been reset.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True


class JsonFormatter(logging.Formatter):
    """Emits one JSON object per line."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "request_id": getattr(record, "request_id", "-"),
        }

        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)

        # Anything passed via logger.info("...", extra={...}) rides along.
        for key, value in record.__dict__.items():
            if key not in _RESERVED:
                payload[key] = value

        return json.dumps(payload, default=str)


def setup_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    handler.addFilter(RequestIDFilter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(get_settings().log_level.upper())

    # uvicorn installs its own handlers on these loggers (with propagate=False) as
    # part of Config.configure_logging(), which runs *before* this module is even
    # imported (uvicorn configures logging, then imports the app). Left alone, their
    # records never reach JsonFormatter: the stream ends up half JSON / half plain
    # text, and every request is logged twice (once here via showtrack.access, once
    # by uvicorn.access). Strip uvicorn's handlers and let the records propagate to
    # our root handler instead.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True

    # RequestIDMiddleware already emits a richer "request completed"/"request failed"
    # line (method, path, status, duration_ms, request_id) for every request, so
    # uvicorn.access's line would be a pure duplicate now that it propagates. Disable
    # it outright rather than emit two JSON lines per request.
    logging.getLogger("uvicorn.access").disabled = True
