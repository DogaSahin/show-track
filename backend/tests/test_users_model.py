import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.users.models import User
from app.users.service import create_user
from tests.factories import make_user


async def test_create_user_inserts_a_row(db_session: AsyncSession) -> None:
    user = await create_user(db_session, username="doga", email="doga@example.com", hashed_password="not-a-real-hash")

    found = (await db_session.execute(select(User).where(User.id == user.id))).scalar_one()
    assert found.username == "doga"
    assert found.id is not None
    assert found.created_at is not None
    assert found.fcm_token is None


async def test_email_uniqueness_is_case_insensitive_and_preserves_case(db_session: AsyncSession) -> None:
    """Both halves matter. A plain UNIQUE would allow the collision; normalise-on-write
    would prevent it but destroy the case the user typed. The functional index does both.
    """
    db_session.add(make_user(username="first", email="Doga@Example.com"))
    await db_session.flush()

    stored = (await db_session.execute(select(User.email))).scalar_one()
    assert stored == "Doga@Example.com"

    db_session.add(make_user(username="second", email="doga@example.com"))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_users_lower_email" in str(excinfo.value)
    await db_session.rollback()


async def test_duplicate_username_is_rejected(db_session: AsyncSession) -> None:
    db_session.add(make_user(username="doga", email="one@example.com"))
    await db_session.flush()

    db_session.add(make_user(username="doga", email="two@example.com"))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_users_username" in str(excinfo.value)
    await db_session.rollback()
