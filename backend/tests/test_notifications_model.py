import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.notifications.models import NotificationTaskStatus, NotificationThreshold
from tests.factories import make_notification_prefs, make_notification_task, make_parents, make_user


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


async def test_a_new_task_starts_pending_with_no_attempts(db_session: AsyncSession) -> None:
    user, media = await make_parents(db_session)
    task = make_notification_task(user.id, media.id)
    db_session.add(task)
    await db_session.flush()

    assert task.status is NotificationTaskStatus.PENDING
    assert task.attempts == 0
    assert task.sent_at is None


async def test_the_same_notification_cannot_be_queued_twice(db_session: AsyncSession) -> None:
    """The constraint the whole notification design rests on: one push per threshold per
    episode, guaranteed by the database rather than by application logic."""
    user, media = await make_parents(db_session)
    db_session.add(make_notification_task(user.id, media.id, episode_number=5))
    await db_session.flush()

    db_session.add(make_notification_task(user.id, media.id, episode_number=5))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_notification_tasks_user_id_media_id_episode_number_threshold" in str(excinfo.value)
    await db_session.rollback()


async def test_both_thresholds_can_be_queued_for_one_episode(db_session: AsyncSession) -> None:
    """The threshold is part of the key on purpose — a 24h reminder and a day-of reminder
    for the same episode are two legitimate rows, not a duplicate."""
    user, media = await make_parents(db_session)
    db_session.add(
        make_notification_task(user.id, media.id, episode_number=5, threshold=NotificationThreshold.TWENTY_FOUR_HOURS)
    )
    db_session.add(make_notification_task(user.id, media.id, episode_number=5, threshold=NotificationThreshold.DAY_OF))

    await db_session.flush()  # must not raise
