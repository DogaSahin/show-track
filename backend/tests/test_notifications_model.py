import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from tests.factories import make_notification_prefs, make_user


async def test_a_user_cannot_have_two_preference_rows(db_session: AsyncSession) -> None:
    user = make_user()
    db_session.add(user)
    await db_session.flush()

    prefs = make_notification_prefs(user.id)
    db_session.add(prefs)
    await db_session.flush()
    # Asserted here rather than in a test of its own: the row already exists, so checking
    # the server default costs nothing extra.
    assert prefs.push_enabled is True

    db_session.add(make_notification_prefs(user.id))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_notification_prefs_user_id" in str(excinfo.value)
    await db_session.rollback()
