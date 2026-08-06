from fastapi import FastAPI

from app.library import routes as library_routes
from app.logging import setup_logging
from app.media import routes as media_routes
from app.middleware import RequestIDMiddleware
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

app = FastAPI(title="ShowTrack API", version="0.1.0")
app.add_middleware(RequestIDMiddleware)

for router in DOMAIN_ROUTERS:
    app.include_router(router, prefix="/v1")


@app.get("/health", tags=["infra"])
async def health() -> dict[str, str]:
    return {"status": "ok"}
