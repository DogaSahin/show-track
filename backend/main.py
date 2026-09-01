from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from starlette.requests import Request
from starlette.responses import PlainTextResponse, Response

from app.db import dispose_engine
from app.errors import register_exception_handlers
from app.groups import routes as groups_routes
from app.http import close_http_client
from app.library import routes as library_routes
from app.logging import setup_logging
from app.media import routes as media_routes
from app.media.providers import get_providers, reset_providers
from app.middleware import REQUEST_ID_HEADER, RequestIDMiddleware
from app.notifications import routes as notifications_routes
from app.recommendations import routes as recommendations_routes
from app.sync import routes as sync_routes
from app.sync import scheduler as scheduler_module
from app.sync.scheduler import start_scheduler
from app.users import routes as users_routes
from app.users.dependencies import get_current_user

DOMAIN_ROUTERS = (
    users_routes.router,
    media_routes.router,
    library_routes.router,
    library_routes.reviews_router,
    sync_routes.router,
    notifications_routes.router,
    recommendations_routes.router,
    groups_routes.router,
)

setup_logging()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup builds the provider registry; the database engine still builds lazily on first
    use. Shutdown releases the database connection pool and the outbound HTTP pool, so a reload
    or redeploy leaks neither.
    """
    # Eager on purpose, unlike everything else here. build_registry logs the "TMDB_API_KEY is not
    # set" warning, which is the whole mitigation for a mistyped variable name degrading search
    # to AniList-only; emitted lazily it lands in the middle of request traffic hours after boot,
    # where nobody is looking. A warning nobody reads is not a mitigation.
    get_providers()
    # Started AFTER the provider registry, because the sync job needs it.
    scheduler = start_scheduler()
    yield
    if scheduler is not None:
        # shutdown(wait=False) CANCELS in-flight jobs and returns; APScheduler's AsyncIOExecutor
        # cannot honour wait=True without being a coroutine. Cancellation lands on the next loop
        # iteration, so without the drain a cancelled job's `finally` — the advisory unlock, the
        # session close — would run AFTER dispose_engine() had torn down the pool. Draining is
        # what makes "shut down before dispose_engine" actually true rather than merely ordered.
        scheduler.shutdown(wait=False)
        await scheduler_module.drain_inflight()
    await dispose_engine()
    await close_http_client()
    reset_providers()


app = FastAPI(title="ShowTrack API", version="0.1.0", lifespan=lifespan)
app.add_middleware(RequestIDMiddleware)

# Domain and provider failures become statuses here rather than in four route bodies. The
# response returns through RequestIDMiddleware's `call_next`, so it takes that middleware's
# `else` branch and is logged as "request completed" with a real status, rather than as an
# untyped "request failed".
register_exception_handlers(app)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> Response:
    """Reproduces Starlette's default 500 response, plus the X-Request-ID header.

    RequestIDMiddleware's `finally` has already reset the contextvar by the time this
    runs (the exception propagates out through ServerErrorMiddleware, which wraps this
    middleware), so the ID is recovered from `request.state` instead.
    """
    response = PlainTextResponse("Internal Server Error", status_code=500)
    response.headers[REQUEST_ID_HEADER] = getattr(request.state, "request_id", "-")
    return response


# Protection is a property of where a router is mounted, not of a decorator on each route.
# A route added in a later phase to one of these routers is protected because it joined a
# protected router. tests/test_auth_protection.py checks this two ways: a direct check that
# get_current_user is present in the route's mount-level dependency list — which runs for EVERY
# route including `{param}` ones, since it is pure route-object inspection and needs no real id
# — and, for routes with no `{param}`, an HTTP-level 401 without a token. The former is what
# actually catches this dependencies=[...] argument being dropped, since some handlers also
# depend on get_current_user for their own data needs and would otherwise mask the loss from an
# HTTP-only check. Routes with no HTTP methods at all (e.g. a Mount) are covered by neither.
for router in DOMAIN_ROUTERS:
    app.include_router(router, prefix="/v1", dependencies=[Depends(get_current_user)])

app.include_router(users_routes.auth_router, prefix="/v1")

# Mounted separately from DOMAIN_ROUTERS, so tests/test_health.py's "eight domain routers with
# eight domain prefixes" invariant keeps meaning what it says — /debug is not a domain. Explicit
# dependencies=, because this router does not inherit the mounting loop's auth above.
app.include_router(sync_routes.debug_router, prefix="/v1", dependencies=[Depends(get_current_user)])


@app.get("/health", tags=["infra"])
async def health() -> dict[str, str]:
    return {"status": "ok"}
