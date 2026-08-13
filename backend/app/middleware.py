import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import ClientDisconnect, Request
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

        client = request.client
        context = {
            "method": request.method,
            "path": request.url.path,
            # Request.client returns None when the ASGI scope has no "client" key, so
            # this must not assume a value is present.
            "client_addr": f"{client.host}:{client.port}" if client else "-",
            "http_version": request.scope.get("http_version", "-"),
        }

        try:
            response = await call_next(request)
        except ClientDisconnect:
            logger.warning(
                "client disconnected",
                extra={**context, "duration_ms": round((time.perf_counter() - started) * 1000, 2)},
            )
            raise
        except Exception:
            logger.exception(
                "request failed",
                extra={**context, "duration_ms": round((time.perf_counter() - started) * 1000, 2)},
            )
            raise
        else:
            logger.info(
                "request completed",
                extra={
                    **context,
                    "status": response.status_code,
                    "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                },
            )
            response.headers[REQUEST_ID_HEADER] = request_id
            return response
        finally:
            request_id_var.reset(token)
