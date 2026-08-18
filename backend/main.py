from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from starlette.requests import Request
from starlette.responses import PlainTextResponse, Response

from app.db import dispose_engine
from app.library import routes as library_routes
from app.logging import setup_logging
from app.media import routes as media_routes
from app.middleware import REQUEST_ID_HEADER, RequestIDMiddleware
from app.notifications import routes as notifications_routes
from app.recommendations import routes as recommendations_routes
from app.sync import routes as sync_routes
from app.users import routes as users_routes

DOMAIN_ROUTERS = (
    users_routes.router,
    media_routes.router,
    library_routes.router,
    sync_routes.router,
    notifications_routes.router,
    recommendations_routes.router,
)

setup_logging()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup does nothing — the engine builds lazily on first use. Shutdown releases the
    connection pool so a reload or redeploy does not leak Postgres connections.
    """
    yield
    await dispose_engine()


app = FastAPI(title="ShowTrack API", version="0.1.0", lifespan=lifespan)
app.add_middleware(RequestIDMiddleware)


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


for router in DOMAIN_ROUTERS:
    app.include_router(router, prefix="/v1")

# Deliberately outside DOMAIN_ROUTERS: that loop is where the auth dependency gets
# attached, and register/login must stay reachable without a token.
app.include_router(users_routes.auth_router, prefix="/v1")


@app.get("/health", tags=["infra"])
async def health() -> dict[str, str]:
    return {"status": "ok"}
