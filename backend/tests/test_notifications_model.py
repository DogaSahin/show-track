from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.notifications.models import NotificationTask, NotificationTaskStatus, NotificationThreshold
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
    """The factory sets neither `status` nor `attempts`: both come back from their
    `server_default` via RETURNING, not from anything Python assigned. `sent_at` is
    asserted `None` for a different reason — nothing sets it at insert, server or
    client — so that assertion checks that no default was accidentally added, not
    that one fired."""
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

    assert "uq_notification_tasks_dedup" in str(excinfo.value)
    await db_session.rollback()


async def test_both_thresholds_can_be_queued_for_one_episode(db_session: AsyncSession) -> None:
    """The threshold is part of the key on purpose — a 24h reminder and an airing-soon
    reminder for the same episode are two legitimate rows, not a duplicate."""
    user, media = await make_parents(db_session)
    db_session.add(
        make_notification_task(user.id, media.id, episode_number=5, threshold=NotificationThreshold.TWENTY_FOUR_HOURS)
    )
    db_session.add(
        make_notification_task(user.id, media.id, episode_number=5, threshold=NotificationThreshold.AIRING_SOON)
    )

    await db_session.flush()  # must not raise


async def test_a_rescheduled_episode_can_be_queued_again(db_session: AsyncSession) -> None:
    """Decision 5-C, and the entire reason airs_on joins the dedup key.

    Under the Phase 1 key, an episode delayed after its 24h notification was created could never
    be enqueued again: the user was told "airs in 24 hours", it did not, and no notification ever
    fired for the true date. Anime episodes are delayed routinely, so that failed in exactly the
    case an airing tracker exists for.
    """
    user, media = await make_parents(db_session)
    original = datetime(2026, 9, 25, tzinfo=UTC)
    db_session.add(make_notification_task(user.id, media.id, episode_number=5, airs_on=original))
    await db_session.flush()

    delayed = original + timedelta(days=7)
    db_session.add(make_notification_task(user.id, media.id, episode_number=5, airs_on=delayed))

    await db_session.flush()  # must NOT raise: a different air DATE is a different key

    assert await db_session.scalar(select(func.count()).select_from(NotificationTask)) == 2


async def test_provider_jitter_does_not_make_a_new_key(db_session: AsyncSession) -> None:
    """Decision 5-L. AniList revises airingAt by seconds for ordinary corrections; a precise key
    would mint a fresh notification for every nudge. The column stores the DATE, so two tasks for
    the same air date collide however the time moved within it.
    """
    user, media = await make_parents(db_session)
    day = datetime(2026, 9, 25, tzinfo=UTC)
    db_session.add(make_notification_task(user.id, media.id, episode_number=5, airs_on=day))
    await db_session.flush()

    db_session.add(make_notification_task(user.id, media.id, episode_number=5, airs_on=day))
    with pytest.raises(IntegrityError):
        await db_session.flush()

    await db_session.rollback()
