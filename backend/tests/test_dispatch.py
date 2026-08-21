import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app.library.models import UserMedia
from app.media.models import Media, MediaStatus
from app.notifications import service
from app.notifications.models import (
    NotificationPrefs,
    NotificationTask,
    NotificationTaskStatus,
    NotificationThreshold,
    PushTarget,
    airs_on_for,
)
from app.notifications.transport import PushMessage, TransportPermanent, TransportRetryable
from app.sync.locks import DISPATCH_LOCK_KEY, advisory_lock
from app.users.models import User
from tests.factories import (
    make_media,
    make_notification_prefs,
    make_notification_task,
    make_push_target,
    make_user,
    make_user_media,
)

# A fixed "now", passed in rather than read from the clock, for the same reason scan_thresholds
# takes one: a test whose expected counts depend on the wall clock fails on a slow CI runner and
# nowhere else.
NOW = datetime(2026, 8, 21, 12, 0, tzinfo=UTC)


class FakeTransport:
    """Records sends; raises on demand. The suite never constructs an httpx request for the
    dispatcher — the transport boundary is the mock point (CLAUDE.md guardrails).
    """

    name = "fake"

    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.sent: list[tuple[str, PushMessage]] = []

    async def send(self, target: str, message: PushMessage) -> None:
        if self.error is not None:
            raise self.error
        self.sent.append((target, message))


@dataclass
class Queued:
    user: User
    media: Media
    user_media: UserMedia
    prefs: NotificationPrefs
    task: NotificationTask


async def _queued(db_session, *, airs_at=NOW + timedelta(hours=20), episode=12, targets=1) -> Queued:
    """A user with prefs, a tracked title airing soon, N registered targets, and one pending
    task — the state the threshold scan leaves behind.

    The per-call `tag` is not decoration: `make_user`'s defaults are fixed, so two un-tagged users
    in one transaction collide on the unique username/email constraint.
    """
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    media = make_media(
        external_id=tag,
        status=MediaStatus.AIRING,
        next_episode_number=episode,
        next_episode_date=airs_at,
    )
    db_session.add_all([user, media])
    await db_session.flush()

    user_media = make_user_media(user.id, media.id)
    prefs = make_notification_prefs(user.id, push_enabled=True)
    db_session.add_all([user_media, prefs])
    for index in range(targets):
        db_session.add(make_push_target(user.id, target=f"topic-{tag}-{index}"))

    task = make_notification_task(
        user.id,
        media.id,
        episode_number=episode,
        threshold=NotificationThreshold.TWENTY_FOUR_HOURS,
        airs_on=airs_on_for(airs_at),
    )
    db_session.add(task)
    await db_session.flush()
    return Queued(user=user, media=media, user_media=user_media, prefs=prefs, task=task)


async def test_a_due_task_is_sent_and_marked(db_session):
    state = await _queued(db_session)
    transport = FakeTransport()

    summary = await service.dispatch_once(db_session, transport, now=NOW)

    assert (summary.sent, summary.skipped, summary.expired) == (1, 0, 0)
    assert len(transport.sent) == 1
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.SENT
    assert state.task.sent_at == NOW


async def test_a_rescheduled_episode_is_skipped_not_sent(db_session):
    """The core of 6-E. A task is a decision made in the past; the world moved. Sending anyway
    delivers confidently wrong information that is never retracted.
    """
    state = await _queued(db_session)
    state.media.next_episode_date = NOW + timedelta(days=9)
    await db_session.flush()
    transport = FakeTransport()

    summary = await service.dispatch_once(db_session, transport, now=NOW)

    assert (summary.sent, summary.skipped) == (0, 1)
    assert transport.sent == []


async def test_provider_jitter_does_not_skip(db_session):
    """The test that stops someone "tidying" 6-E into a raw timestamp comparison.

    AniList revises airingAt by SECONDS for ordinary corrections. Comparing precise instants
    would skip nearly every notification and look like the feature simply not working.
    """
    state = await _queued(db_session)
    state.media.next_episode_date = state.media.next_episode_date + timedelta(seconds=30)
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert summary.sent == 1


async def test_a_title_removed_from_the_library_is_skipped(db_session):
    state = await _queued(db_session)
    await db_session.delete(state.user_media)
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.sent, summary.skipped) == (0, 1)


async def test_push_disabled_after_enqueue_is_skipped(db_session):
    """Honours the user's intent immediately rather than at the next scan. A queued task must not
    outrank a preference the user changed thirty seconds ago.
    """
    state = await _queued(db_session)
    state.prefs.push_enabled = False
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.sent, summary.skipped) == (0, 1)


async def test_an_episode_that_already_aired_expires(db_session):
    """Distinct from skipped (6-F): this one says WE were too slow, not that the world changed.
    A push about an episode that started two hours ago has negative value.
    """
    state = await _queued(db_session, airs_at=NOW - timedelta(hours=2))

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.sent, summary.expired) == (0, 1)
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.EXPIRED


async def test_an_episode_the_pointer_has_advanced_past_expires(db_session):
    """Episode 12 aired at 10:00, it is now 12:00, and the sync job has already advanced the
    pointer to 13. That is OUR lateness, so EXPIRED.

    The earlier `task.airs_on < airs_on_for(now)` test called this SKIPPED: both sides are
    date-truncated to the same midnight, so `<` was false. Backoff totals ~30 minutes, so a
    genuinely late task is almost always same-day — which made that branch's EXPIRED close to
    dead code and the reported status a race with the sync job.
    """
    state = await _queued(db_session, airs_at=NOW - timedelta(hours=2), episode=12)
    state.media.next_episode_number = 13
    state.media.next_episode_date = NOW + timedelta(days=7)
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.expired, summary.skipped) == (1, 0)
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.EXPIRED


async def test_a_series_finale_with_a_cleared_pointer_expires(db_session):
    """The commonest late-task shape there is, and the one the `>` comparison alone got wrong.

    app/sync/service.py writes next_episode_number = None when a show has no next episode, so on a
    finale the pointer is NULL rather than advanced and `None > 12` never ran. NULL here means
    EXPIRED, not "unknown": scan_thresholds cannot enqueue a task while the number is NULL, so a
    NULL observed now is one that was cleared AFTER this task was created — the episode aired and
    there is nothing after it.
    """
    state = await _queued(db_session, airs_at=NOW - timedelta(hours=2), episode=12)
    state.media.next_episode_number = None
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.expired, summary.skipped) == (1, 0)
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.EXPIRED


async def test_an_episode_rescheduled_out_of_a_past_slot_is_skipped_not_expired(db_session):
    """The mirror of the case above, and the other direction the date comparison got wrong.

    The episode was due yesterday and has been pushed to next week. The episode NUMBER is
    unchanged, so nothing aired — the world changed, which is SKIPPED. The date test said EXPIRED
    because the task's own truncated date is behind today's.
    """
    state = await _queued(db_session, airs_at=NOW - timedelta(days=1), episode=12)
    state.media.next_episode_date = NOW + timedelta(days=7)
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.skipped, summary.expired) == (1, 0)
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.SKIPPED


async def test_a_user_with_no_targets_is_skipped(db_session):
    """Push enabled but no device registered. Not a failure — there is nowhere to send."""
    await _queued(db_session, targets=0)

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.sent, summary.skipped) == (0, 1)


async def test_two_targets_both_receive_the_push(db_session):
    """The reason push_targets is a table (6-C). Under the old single-column design the second
    device silently stole the first one's notifications.
    """
    await _queued(db_session, targets=2)
    transport = FakeTransport()

    await service.dispatch_once(db_session, transport, now=NOW)

    assert len(transport.sent) == 2


async def test_a_retryable_failure_leaves_the_task_pending_with_backoff(db_session):
    state = await _queued(db_session)

    summary = await service.dispatch_once(db_session, FakeTransport(TransportRetryable("503")), now=NOW)

    assert (summary.sent, summary.retrying) == (0, 1)
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.PENDING
    assert state.task.attempts == 1
    assert state.task.next_attempt_at > NOW


async def test_the_attempt_is_committed_before_the_send(db_session, monkeypatch):
    """6-G, at-least-once — the MECHANISM, not just the resulting state.

    An earlier version of this test asserted `attempts == 1` after the call, which a plain flush
    satisfies just as well as a commit, so it could not fail for the reason its name claimed. A
    flush is undone by the rollback a crash implies: the process would re-claim the same task at
    attempts=0 on every restart, MAX_ATTEMPTS would be unreachable, and the `failed` bucket 6-F
    exists to provide would never be written. So the property is the ORDERING — a commit strictly
    precedes the first send — and that is what is asserted.
    """
    state = await _queued(db_session)
    events: list[str] = []
    real_commit = db_session.commit

    async def recording_commit() -> None:
        events.append("commit")
        await real_commit()

    monkeypatch.setattr(db_session, "commit", recording_commit)

    class RecordingTransport(FakeTransport):
        async def send(self, target: str, message: PushMessage) -> None:
            events.append("send")
            await super().send(target, message)

    await service.dispatch_once(db_session, RecordingTransport(), now=NOW)

    assert "send" in events, "the transport was never called; this test proves nothing"
    assert "commit" in events, "the attempt was never committed before the transport was touched"
    assert events.index("commit") < events.index("send"), events
    await db_session.refresh(state.task)
    assert state.task.attempts == 1


async def test_the_final_attempt_marks_the_task_failed(db_session):
    state = await _queued(db_session)
    state.task.attempts = service.MAX_ATTEMPTS - 1
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(TransportRetryable("503")), now=NOW)

    assert summary.failed == 1
    await db_session.refresh(state.task)
    assert state.task.status is NotificationTaskStatus.FAILED


async def test_a_permanent_failure_deletes_the_target(db_session):
    """An unknown topic will never succeed. Retrying it forever accumulates dead rows and burns
    an attempt budget that belongs to the live targets.
    """
    state = await _queued(db_session)

    await service.dispatch_once(db_session, FakeTransport(TransportPermanent("404")), now=NOW)

    remaining = await db_session.scalars(select(PushTarget).where(PushTarget.user_id == state.user.id))
    assert list(remaining) == []


async def test_a_task_not_yet_due_is_left_alone(db_session):
    """next_attempt_at in the future means the backoff has not elapsed. Claiming it anyway would
    make the backoff decorative.
    """
    state = await _queued(db_session)
    state.task.next_attempt_at = NOW + timedelta(minutes=10)
    await db_session.flush()

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert summary.claimed == 0


async def test_a_capped_batch_reports_what_it_left_behind(db_session, monkeypatch):
    """The "no silent caps" rule: a run that hit DISPATCH_BATCH_SIZE must not read as a
    completed one.

    The constant is monkeypatched down rather than lowered in the source — 100 is the production
    value and this test is about the reporting, not the size. Note the count is only taken when
    the cap was ACTUALLY hit, so the common empty-queue run pays nothing for it.
    """
    monkeypatch.setattr(service, "DISPATCH_BATCH_SIZE", 2)
    for _ in range(5):
        await _queued(db_session)

    summary = await service.dispatch_once(db_session, FakeTransport(), now=NOW)

    assert (summary.claimed, summary.remaining) == (2, 3)
    assert summary.sent == 2


async def test_a_contended_run_reports_ran_false():
    """Mirrors Phase 5's lock test, on the entry point the scheduler actually calls. Without the
    lock a second replica double-sends every push — a correctness bug, not a scale concern.
    """
    async with advisory_lock(DISPATCH_LOCK_KEY) as held:
        assert held is True
        summary = await service.run_dispatch(FakeTransport())

    assert summary.ran is False
