from datetime import UTC, datetime

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.config import get_settings
from app.groups.models import Group, GroupMember, GroupRole
from app.users.models import User


async def _a_group(auth_client) -> dict:
    return (await auth_client.post("/v1/groups", json={"name": "Household"})).json()


async def test_a_group_invite_code_creates_an_account_and_a_membership(auth_client, client, db_session):
    created = await _a_group(auth_client)

    response = await client.post(
        "/v1/auth/register",
        json={
            "username": "housemate",
            "email": "housemate@example.com",
            "password": "housemate-password",
            "invite_code": created["invite_code"],
        },
    )

    assert response.status_code == 201
    user = await db_session.scalar(select(User).where(User.email == "housemate@example.com"))
    assert user is not None
    member = await db_session.scalar(select(GroupMember).where(GroupMember.user_id == user.id))
    assert member is not None
    assert member.role is GroupRole.MEMBER, "an invite code must never mint an owner"


async def test_the_server_registration_code_still_works_and_joins_nothing(client, db_session):
    response = await client.post(
        "/v1/auth/register",
        json={
            "username": "solo",
            "email": "solo@example.com",
            "password": "solo-password",
            "invite_code": get_settings().registration_code,
        },
    )

    assert response.status_code == 201
    user = await db_session.scalar(select(User).where(User.email == "solo@example.com"))
    assert await db_session.scalar(select(GroupMember).where(GroupMember.user_id == user.id)) is None


async def test_a_duplicate_email_leaves_no_membership_behind(auth_client, client, db_session):
    """The FIRST write failing adds nothing to the group.

    Deliberately NOT the atomicity claim, on two counts. The duplicate email fails inside
    create_account, which raises before returning, so add_member never runs and there is no second
    write for a premature commit to strand. And the harm this once claimed to catch — a membership
    pointing at no user — is impossible in ANY arrangement: GroupMember.user_id carries a
    non-deferrable foreign key, so the database refuses it. The real harm runs the other way, an
    account with no group, and test_a_failed_join_leaves_no_account_behind is what pins it.

    What this pins is narrower and still worth having: the unique-constraint failure is translated
    to a 409 and leaves the membership table untouched.
    """
    created = await _a_group(auth_client)
    body = {
        "username": "housemate",
        "email": "housemate@example.com",
        "password": "housemate-password",
        "invite_code": created["invite_code"],
    }
    assert (await client.post("/v1/auth/register", json=body)).status_code == 201

    duplicate = await client.post("/v1/auth/register", json={**body, "username": "housemate2"})

    assert duplicate.status_code == 409
    memberships = list(await db_session.scalars(select(GroupMember)))
    # The owner, plus exactly one successful housemate. The failed attempt added nothing.
    assert len(memberships) == 2


async def test_an_expired_group_code_cannot_register(auth_client, client, db_session):
    import uuid as _uuid

    created = await _a_group(auth_client)
    group = await db_session.get(Group, _uuid.UUID(created["id"]))
    group.invite_code_expires_at = datetime(2000, 1, 1, tzinfo=UTC)
    await db_session.flush()

    response = await client.post(
        "/v1/auth/register",
        json={
            "username": "late",
            "email": "late@example.com",
            "password": "late-password",
            "invite_code": created["invite_code"],
        },
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid invite code"


async def test_a_failed_join_leaves_no_account_behind(auth_client, client, db_session, monkeypatch):
    """The atomicity claim: the SECOND write failing must discard the FIRST.

    add_member has a real failure mode, not merely the unique constraint on (group_id, user_id)
    that a brand-new user cannot hit. GroupMember.group_id carries a NON-DEFERRABLE foreign key to
    groups.id, and remove_member deletes the group outright when its last member leaves. A register
    request that resolved the group before that deletion committed blocks on the FOR KEY SHARE lock
    the INSERT takes against Task 5's SELECT ... FOR UPDATE, then fails the FK deterministically.

    One transaction makes that a 500 with nothing persisted. Two transactions would leave a
    committed account that consumed an invite and belongs to no group.

    The rollback below is what discriminates: conftest binds the session with
    join_transaction_mode="create_savepoint", so a route-level commit RELEASES the savepoint and
    survives this, while work that was only flushed does not.
    """
    created = await _a_group(auth_client)

    async def _fk_violation(*args, **kwargs):
        raise IntegrityError("INSERT INTO group_members", {}, Exception("group_members_group_id_fkey"))

    monkeypatch.setattr("app.groups.service.add_member", _fk_violation)

    with pytest.raises(IntegrityError):
        await client.post(
            "/v1/auth/register",
            json={
                "username": "housemate",
                "email": "housemate@example.com",
                "password": "housemate-password",
                "invite_code": created["invite_code"],
            },
        )

    await db_session.rollback()
    assert await db_session.scalar(select(User).where(User.email == "housemate@example.com")) is None
