from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.notifications import service
from app.notifications.schemas import PrefsRead, PrefsUpdate
from app.users.dependencies import get_current_user
from app.users.models import User

router = APIRouter(prefix="/notifications", tags=["notifications"])

# Inside Annotated, not as a default value — ruff B008. Same convention as app/library/routes.py.
SessionDep = Annotated[AsyncSession, Depends(get_session)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]


@router.get("/prefs", response_model=PrefsRead)
async def get_prefs(session: SessionDep, current_user: CurrentUserDep) -> PrefsRead:
    return PrefsRead(push_enabled=await service.read_prefs(session, user_id=current_user.id))


@router.patch("/prefs", response_model=PrefsRead)
async def update_prefs(payload: PrefsUpdate, session: SessionDep, current_user: CurrentUserDep) -> PrefsRead:
    enabled = await service.set_prefs(session, user_id=current_user.id, push_enabled=payload.push_enabled)
    await session.commit()
    return PrefsRead(push_enabled=enabled)
