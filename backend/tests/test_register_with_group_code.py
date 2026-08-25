from datetime import UTC, datetime

from sqlalchemy import select

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
    """The atomicity claim. The account write fails on the unique constraint; if the two writes
    were not one transaction, the group could gain a membership pointing at no user."""
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
