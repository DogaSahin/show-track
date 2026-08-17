from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.users import service
from app.users.schemas import LoginRequest, RegisterRequest, TokenPair, UserOut

router = APIRouter(prefix="/users", tags=["users"])
auth_router = APIRouter(prefix="/auth", tags=["auth"])

# One body for every authentication failure. Distinguishing "no such account" from "wrong
# password" tells an attacker which addresses are registered.
_INVALID_CREDENTIALS = "invalid email or password"

# `Depends(...)` lives in the annotation, not a default value: ruff's B008 (function-call-in-
# default-argument, part of this project's selected `B` rules) flags `= Depends(get_session)`
# but not `Annotated[AsyncSession, Depends(get_session)]`.
SessionDep = Annotated[AsyncSession, Depends(get_session)]


@auth_router.post("/register", response_model=UserOut, status_code=status.HTTP_201_CREATED)
async def register(payload: RegisterRequest, session: SessionDep) -> UserOut:
    try:
        user = await service.register_user(
            session,
            username=payload.username,
            email=payload.email,
            password=payload.password,
            invite_code=payload.invite_code,
        )
    except service.RegistrationError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail) from exc

    await session.commit()
    return UserOut.model_validate(user)


@auth_router.post("/login", response_model=TokenPair)
async def login(payload: LoginRequest, session: SessionDep) -> TokenPair:
    user = await service.authenticate(session, email=payload.email, password=payload.password)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_INVALID_CREDENTIALS)

    access, refresh = await service.issue_token_pair(session, user)
    await session.commit()
    return TokenPair(access_token=access, refresh_token=refresh)
