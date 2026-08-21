import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.notifications import service
from app.notifications.models import PushTarget
from app.notifications.schemas import PrefsRead, PrefsUpdate, TargetCreate, TargetCreated, TargetRead
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


@router.post("/targets", response_model=TargetCreated, status_code=status.HTTP_201_CREATED)
async def register_target(payload: TargetCreate, session: SessionDep, current_user: CurrentUserDep) -> PushTarget:
    """Returns the topic ONCE. There is no endpoint that will show it again."""
    target = await service.create_target(session, user_id=current_user.id, label=payload.label)
    await session.commit()
    return target


@router.get("/targets", response_model=list[TargetRead])
async def list_targets(session: SessionDep, current_user: CurrentUserDep) -> list[PushTarget]:
    return await service.list_targets(session, user_id=current_user.id)


@router.delete("/targets/{target_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_target(target_id: uuid.UUID, session: SessionDep, current_user: CurrentUserDep) -> None:
    if not await service.delete_target(session, user_id=current_user.id, target_id=target_id):
        # 404 whether it is missing or someone else's — see delete_target's docstring.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no such target")
    await session.commit()
