from collections.abc import Mapping
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider
from app.sync import service
from app.sync.schemas import SyncSummary
from app.users.dependencies import get_current_user
from app.users.models import User

# `router` keeps its /sync prefix and stays routeless. tests/test_health.py asserts six DOMAIN
# routers with six domain prefixes, and /debug is not a domain — renaming this one to fit a debug
# path would weaken an invariant rather than satisfy it.
router = APIRouter(prefix="/sync", tags=["sync"])

# Design doc §8 specifies POST /v1/debug/sync. Mounted separately in main.py with an explicit auth
# dependency — the same shape users/routes.py uses for auth_router. If other modules ever need
# debug routes, this becomes a shared debug router assembled in main.
debug_router = APIRouter(prefix="/debug", tags=["debug"])

SessionDep = Annotated[AsyncSession, Depends(get_session)]
ProvidersDep = Annotated[Mapping[MediaSource, MediaProvider], Depends(get_providers)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]


@debug_router.post("/sync", response_model=SyncSummary)
async def trigger_sync(session: SessionDep, providers: ProvidersDep, current_user: CurrentUserDep) -> SyncSummary:
    """Run the sync job now, outside its schedule.

    Calls run_sync, which takes the same advisory lock the scheduler does — so a manual trigger
    cannot run concurrently with a scheduled one, which is the entire point of the lock. If the
    scheduled job holds it, this answers `ran: false` rather than doing the work twice.
    """
    # Decision 4-M. get_current_user's read has already begun a transaction, and run_sync below can
    # spend minutes in provider calls. Releasing it first stops a pooled connection sitting idle in
    # transaction for the duration; run_sync opens its own session for the work.
    await session.rollback()
    return await service.run_sync(providers)
