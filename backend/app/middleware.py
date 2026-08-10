import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.logging import request_id_var

REQUEST_ID_HEADER = "X-Request-ID"

logger = logging.getLogger("showtrack.access")


class RequestIDMiddleware(BaseHTTPMiddleware):
    """Assigns a request ID, exposes it to logging, echoes it, and logs the request.

    ``request.state.request_id`` is set in addition to the contextvar because an
    unhandled exception unwinds back out through this middleware's ``finally`` (which
    resets the contextvar) before FastAPI's server-error handling ever runs —
    ``ServerErrorMiddleware`` wraps this middleware, not the other way round. The error
    handler needs a way to recover the ID that survives that reset: ``request.state`` is
    backed by the ASGI scope dict, so it stays visible to any ``Request`` built from the
    same scope afterwards, including the one the error handler constructs.
    """

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())
        request.state.request_id = request_id
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            logger.exception(
                "request failed",
                extra={
                    "method": request.method,
                    "path": request.url.path,
                    "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                },
            )
            raise
        else:
            logger.info(
                "request completed",
                extra={
                    "method": request.method,
                    "path": request.url.path,
                    "status": response.status_code,
                    "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                },
            )
            response.headers[REQUEST_ID_HEADER] = request_id
            return response
        finally:
            request_id_var.reset(token)
