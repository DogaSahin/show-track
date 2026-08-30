import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.notifications import service
from app.notifications.models import PushTarget, PushTransport
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
async def register_target(
    payload: TargetCreate,
    session: SessionDep,
    current_user: CurrentUserDep,
    response: Response,
) -> PushTarget:
    """Registers a device for either transport.

    For `ntfy` the server mints the topic and returns it ONCE — there is no endpoint that will
    show it again.

    For `unifiedpush` the distributor already minted the endpoint, so registration is IDEMPOTENT
    on it (decision A-O) and the status code carries which of the two happened: 201 when a row
    was inserted, 200 when the endpoint was already registered to this user. Both return the same
    body. The client cannot avoid re-registering — `onNewEndpoint` fires on every app start, not
    once — so "you already told me this" has to be a success, not a 409, or the app would log an
    error on every cold start.

    `response.status_code` rather than two routes or a `JSONResponse`: the decorator's 201 is the
    default and this overrides it for the one case, while `response_model=TargetCreated` keeps
    serialising the ORM object. Returning a JSONResponse directly would bypass the response model
    and hand back the raw row — including fields TargetRead deliberately withholds.
    """
    if payload.transport is PushTransport.UNIFIEDPUSH:
        # `payload.target` is non-empty here by construction: TargetCreate's validator rejects a
        # unifiedpush body without one before this function is entered. No defensive check, for
        # the same reason no route re-checks that `label` is under 64 characters.
        target, created = await service.create_unifiedpush_target(
            session, user_id=current_user.id, endpoint=payload.target, label=payload.label
        )
        if not created:
            response.status_code = status.HTTP_200_OK
        await session.commit()
        return target

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
