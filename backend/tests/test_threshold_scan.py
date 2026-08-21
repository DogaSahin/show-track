import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select

from app.media.models import MediaSource, MediaStatus
from app.notifications.models import NotificationTask, NotificationThreshold
from app.sync import service
from tests.factories import make_media, make_notification_prefs, make_user, make_user_media

NOW = datetime(2026, 12, 1, 12, 0, tzinfo=UTC)
SOON = 6  # notify_soon_hours, passed explicitly so no test depends on a developer's .env


async def _tracking_user(db_session, *, airs_in: timedelta | None, push_enabled: bool = True, episode: int = 7):
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    media = make_media(
        source=MediaSource.ANILIST,
        external_id=tag,
        status=MediaStatus.AIRING,
        next_episode_number=episode,
        next_episode_date=None if airs_in is None else NOW + airs_in,
    )
    db_session.add_all([user, media])
    await db_session.flush()
    db_session.add_all(
        [make_user_media(user.id, media.id), make_notification_prefs(user.id, push_enabled=push_enabled)]
    )
    await db_session.flush()
    return user, media


async def test_an_episode_inside_the_horizon_enqueues_a_24h_task(db_session):
    await _tracking_user(db_session, airs_in=timedelta(hours=20))

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.ran is True
    assert summary.enqueued == 1
    task = await db_session.scalar(select(NotificationTask))
    assert task.threshold is NotificationThreshold.TWENTY_FOUR_HOURS
    # UTC MIDNIGHT of the air date, not the air time — the only test pinning the truncation that
    # makes provider jitter a no-op.
    assert task.airs_on == datetime(2026, 12, 2, tzinfo=UTC)


async def test_an_episode_beyond_the_horizon_enqueues_nothing(db_session):
    await _tracking_user(db_session, airs_in=timedelta(hours=30))

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.enqueued == 0


async def test_an_episode_that_already_aired_enqueues_nothing(db_session):
    """A notification about an episode that has already aired is worse than none."""
    await _tracking_user(db_session, airs_in=timedelta(hours=-2))

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.enqueued == 0


async def test_scanning_twice_enqueues_once(db_session):
    """Dedup is the constraint, not an application-side "have we sent this" check. Every scan
    between the crossing and the airing re-derives the same task; being refused is the healthy
    steady state.
    """
    await _tracking_user(db_session, airs_in=timedelta(hours=20))

    first = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)
    second = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert (first.enqueued, second.enqueued) == (1, 0)
    assert second.already_queued == 1
    assert await db_session.scalar(select(func.count()).select_from(NotificationTask)) == 1


async def test_a_rescheduled_episode_enqueues_again(db_session):
    """Decision 5-C end to end: after a delay the air DATE changes, which makes a different dedup
    key, which earns a fresh notification. Under the Phase 1 key the user got one wrong push and
    then silence.
    """
    _, media = await _tracking_user(db_session, airs_in=timedelta(hours=20))
    await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    media.next_episode_date = NOW + timedelta(days=7)
    await db_session.flush()
    later = NOW + timedelta(days=6, hours=12)
    summary = await service.scan_thresholds(db_session, now=later, soon_hours=SOON)

    assert summary.enqueued == 1
    assert await db_session.scalar(select(func.count()).select_from(NotificationTask)) == 2


async def test_provider_jitter_does_not_enqueue_a_second_notification(db_session):
    """Decision 5-L. AniList revises airingAt by seconds for ordinary corrections; a precise key
    would mint a new notification for each nudge — six pushes for one episode after three.
    """
    _, media = await _tracking_user(db_session, airs_in=timedelta(hours=20))
    first = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    media.next_episode_date = media.next_episode_date + timedelta(seconds=45)
    await db_session.flush()
    second = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert first.enqueued == 1
    assert second.enqueued == 0
    assert second.already_queued == 1


async def test_push_disabled_enqueues_nothing(db_session):
    await _tracking_user(db_session, airs_in=timedelta(hours=20), push_enabled=False)

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.enqueued == 0


async def test_a_user_with_no_prefs_row_enqueues_nothing(db_session):
    """The join is inner on purpose: no prefs row means push was never configured, and inventing
    a default here would send pushes nobody asked for.
    """
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    media = make_media(
        external_id=tag, status=MediaStatus.AIRING, next_episode_number=7, next_episode_date=NOW + timedelta(hours=5)
    )
    db_session.add_all([user, media])
    await db_session.flush()
    db_session.add(make_user_media(user.id, media.id))
    await db_session.flush()

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.enqueued == 0


async def test_an_episode_inside_the_soon_window_enqueues_both_thresholds(db_session):
    """Both thresholds firing for one episode is what two thresholds MEAN; suppression is the
    dispatcher's concern in Phase 6.
    """
    await _tracking_user(db_session, airs_in=timedelta(hours=3))

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert summary.enqueued == 2
    thresholds = set(await db_session.scalars(select(NotificationTask.threshold)))
    assert thresholds == {NotificationThreshold.TWENTY_FOUR_HOURS, NotificationThreshold.AIRING_SOON}


async def test_an_episode_airing_just_after_utc_midnight_still_gets_the_soon_threshold(db_session):
    """Decision 5-M's whole purpose. Under the old calendar rule this episode's window was the
    five minutes between UTC midnight and 00:05, so a fifteen-minute scan would have missed it
    entirely — and nothing would have said so.
    """
    midnight = datetime(2026, 12, 2, 0, 0, tzinfo=UTC)
    await _tracking_user(db_session, airs_in=(midnight + timedelta(minutes=5)) - NOW)

    summary = await service.scan_thresholds(db_session, now=midnight - timedelta(hours=2), soon_hours=SOON)

    thresholds = set(await db_session.scalars(select(NotificationTask.threshold)))
    assert NotificationThreshold.AIRING_SOON in thresholds
    assert summary.enqueued >= 1


async def test_a_title_with_no_air_date_enqueues_nothing(db_session):
    await _tracking_user(db_session, airs_in=None)

    summary = await service.scan_thresholds(db_session, now=NOW, soon_hours=SOON)

    assert (summary.considered, summary.enqueued) == (0, 0)


async def test_a_contended_scan_reports_ran_false():
    """The lock branch on the entry point the scheduler actually calls."""
    from app.sync.locks import THRESHOLD_LOCK_KEY, advisory_lock

    async with advisory_lock(THRESHOLD_LOCK_KEY) as held:
        assert held is True
        summary = await service.run_threshold_scan()

    assert summary.ran is False
    assert summary.enqueued == 0
