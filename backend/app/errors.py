import logging
import math
from collections.abc import Awaitable, Callable

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from starlette.requests import Request
from starlette.responses import Response

from app.library.service import MediaMissing
from app.media.providers.errors import (
    ProviderError,
    ProviderRateLimited,
    ProviderTimeout,
    UserListNotAvailable,
)
from app.media.service import MediaNotFound, MediaSourceNotConfigured

logger = logging.getLogger(__name__)

# The only place in the application where an exception becomes a status code.
#
# Starlette resolves a handler by walking type(exc).__mro__ and taking the first registered
# match, so registering the ProviderError base alongside its subclasses is safe and the
# REGISTRATION ORDER HERE IS IRRELEVANT: a ProviderTimeout finds its own 504 before the base's
# 502, because ProviderTimeout comes first in its own MRO.
#
# The detail is a fixed string, never str(exc). Provider exception messages embed the upstream
# URL and the transport's own error text — internal state that is not part of this API's
# contract and that changes whenever a provider client changes. Pinning the detail means the
# response body cannot widen when an exception message does.
#
# Measured, so the next reader does not have to re-derive it: no credential reaches these
# messages TODAY. TMDB's key travels via httpx `params=`, while ProviderUnavailable interpolates
# the pre-params URL, and httpx transport exceptions carry no URL in their own message. This is
# a containment policy against future drift, not a fix for a live leak — do not weaken it on the
# grounds that nothing leaks right now.
#
# MediaNotFound and MediaMissing share a status and a detail and are deliberately NOT merged.
# They are different facts about different systems: MediaNotFound means "the provider answered
# and has no title with that id", and is only ever raised after an upstream call; MediaMissing
# means "no local `media` row for this internal id", and is raised from an FK 23503. Collapsing
# them would couple an upstream-provider contract to a local-FK contract on the strength of a
# status code they happen to share today.
HANDLED: dict[type[Exception], tuple[int, str]] = {
    MediaSourceNotConfigured: (503, "media source is not configured on this server"),
    MediaNotFound: (404, "no such title"),
    MediaMissing: (404, "no such title"),
    UserListNotAvailable: (404, "no public list for that username"),
    ProviderTimeout: (504, "the upstream provider timed out"),
    ProviderRateLimited: (429, "the upstream provider rate limited this server"),
    ProviderError: (502, "the upstream provider is unavailable"),
}


def _make_handler(status_code: int, detail: str) -> Callable[[Request, Exception], Awaitable[Response]]:
    """A factory, not a closure written inline in the loop below: binding status_code and detail
    as parameters is what stops every handler capturing the loop variable's final value.
    """

    async def handler(request: Request, exc: Exception) -> Response:
        headers: dict[str, str] = {}
        if isinstance(exc, ProviderRateLimited) and exc.retry_after is not None:
            # Retry-After is an integer number of seconds; ceil, because rounding down tells the
            # client to retry while still rate limited. _parse_retry_after guarantees this is
            # finite and non-negative.
            headers["Retry-After"] = str(math.ceil(exc.retry_after))
        # Type and status only. Logging `exc` would put upstream URLs into the log stream, which
        # providers/http.py deliberately avoids ("Never log the full URL"). The exception itself
        # is already logged with its message at the layer that raised it.
        logger.warning("%s -> %s", type(exc).__name__, status_code)
        return JSONResponse({"detail": detail}, status_code=status_code, headers=headers or None)

    return handler


def register_exception_handlers(app: FastAPI) -> None:
    """Called once from main.py.

    These serve HTTP callers only. Phase 5's scheduler job has no request and no handler, so it
    must catch ProviderError itself rather than assuming this net exists.
    """
    for exc_type, (status_code, detail) in HANDLED.items():
        app.add_exception_handler(exc_type, _make_handler(status_code, detail))
