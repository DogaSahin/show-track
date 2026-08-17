import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.users.models import RefreshToken
from tests.factories import make_user


def _token(user_id: uuid.UUID, **overrides: object) -> RefreshToken:
    defaults: dict[str, object] = {
        "user_id": user_id,
        "family_id": uuid.uuid4(),
        "token_hash": uuid.uuid4().hex * 2,  # 64 chars, the sha256 width
        "expires_at": datetime.now(UTC) + timedelta(days=30),
    }
    return RefreshToken(**{**defaults, **overrides})


async def test_a_duplicate_token_hash_is_rejected(db_session: AsyncSession) -> None:
    """The hash is how a presented token is looked up, so two rows sharing one would make
    that lookup ambiguous.
    """
    user = make_user()
    db_session.add(user)
    await db_session.flush()

    shared = uuid.uuid4().hex * 2
    db_session.add(_token(user.id, token_hash=shared))
    await db_session.flush()

    db_session.add(_token(user.id, token_hash=shared))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_refresh_tokens_token_hash" in str(excinfo.value)
    await db_session.rollback()


async def test_a_new_token_is_not_revoked(db_session: AsyncSession) -> None:
    user = make_user()
    db_session.add(user)
    await db_session.flush()

    token = _token(user.id)
    db_session.add(token)
    await db_session.flush()

    assert token.revoked_at is None
    assert token.created_at is not None


async def test_deleting_a_user_cascades_to_their_tokens(db_session: AsyncSession) -> None:
    """No relationship() is declared, so this is the database cascade rather than SQLAlchemy
    nulling the FK in Python — the same arrangement Phase 1 used for episodes.
    """
    from sqlalchemy import delete

    from app.users.models import User

    user = make_user()
    db_session.add(user)
    await db_session.flush()
    db_session.add(_token(user.id))
    await db_session.flush()

    before = (
        await db_session.execute(select(func.count()).select_from(RefreshToken).where(RefreshToken.user_id == user.id))
    ).scalar_one()
    assert before == 1

    await db_session.execute(delete(User).where(User.id == user.id))
    await db_session.flush()

    remaining = (
        await db_session.execute(select(func.count()).select_from(RefreshToken).where(RefreshToken.user_id == user.id))
    ).scalar_one()
    assert remaining == 0
