import logging
import secrets
import uuid
from collections import defaultdict
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_sessionmaker
from app.library.models import UserMedia
from app.media.models import Media
from app.notifications.models import (
    NotificationPrefs,
    NotificationTask,
    NotificationTaskStatus,
    PushTarget,
    PushTransport,
    airs_on_for,
)
from app.notifications.schemas import DispatchSummary
from app.notifications.transport import (
    NotificationTransport,
    PushMessage,
    TransportPermanent,
    TransportRetryable,
)
from app.notifications.unifiedpush import validate_endpoint
from app.sync.locks import DISPATCH_LOCK_KEY, advisory_lock

logger = logging.getLogger(__name__)

# 32 bytes via token_urlsafe -> 43 characters of A-Za-z0-9-_, which is exactly the character set
# ntfy accepts in a topic. Sized for unguessability rather than tidiness: this string is the ONLY
# thing standing between a stranger and the notification stream.
TOPIC_ENTROPY_BYTES = 32


async def read_prefs(session: AsyncSession, *, user_id: uuid.UUID) -> bool:
    """False when no row exists, matching what the threshold scan already does with an absent
    row. Deliberately does NOT create one: a read that writes is a surprise, and creating a row
    here would opt the user in as a side effect of looking.
    """
    enabled = await session.scalar(select(NotificationPrefs.push_enabled).where(NotificationPrefs.user_id == user_id))
    return bool(enabled)


async def set_prefs(session: AsyncSession, *, user_id: uuid.UUID, push_enabled: bool) -> bool:
    """Upsert. Flushes; the caller commits.

    ON CONFLICT rather than read-then-write, for the same reason the notification dedup is a
    constraint: a check-then-insert is racy against a concurrent PATCH from a second device, and
    user_id is unique so the race is an IntegrityError rather than a duplicate row.
    """
    statement = (
        pg_insert(NotificationPrefs)
        .values(user_id=user_id, push_enabled=push_enabled)
        .on_conflict_do_update(index_elements=[NotificationPrefs.user_id], set_={"push_enabled": push_enabled})
        .returning(NotificationPrefs.push_enabled)
    )
    result = await session.scalar(statement)
    await session.flush()
    return bool(result)


async def create_target(session: AsyncSession, *, user_id: uuid.UUID, label: str | None) -> PushTarget:
    """Server-generated topic, never client-supplied (6-L). Flushes; the caller commits.

    Not idempotent on re-registration, and cannot be: the server mints the topic, so there is no
    client-supplied key to be idempotent ON. Registering twice yields two targets and two
    notifications — which is why DELETE exists and why `label` matters.
    """
    target = PushTarget(
        user_id=user_id,
        transport=PushTransport.NTFY,
        target=secrets.token_urlsafe(TOPIC_ENTROPY_BYTES),
        label=label,
    )
    session.add(target)
    await session.flush()
    return target


class TargetOwnedByAnotherUser(Exception):
    """This endpoint is already registered to a different account.

    A distinct outcome from "created" and from "already yours", and it cannot be papered over by
    inserting anyway: `uq_push_targets_transport_target` is GLOBAL (6-D), so the insert would come
    back as an IntegrityError and a 500. Raising here turns a crash into a 409.
    """


async def create_unifiedpush_target(
    session: AsyncSession, *, user_id: uuid.UUID, endpoint: str, label: str | None
) -> tuple[PushTarget, bool]:
    """Returns (target, created). Idempotent on the endpoint. Flushes; the caller commits.

    This is the half of registration `create_target` above cannot be, and the docstring there
    explains why: the server mints the ntfy topic, so there is no client-supplied key to be
    idempotent ON. UnifiedPush reverses that — the distributor mints the endpoint and re-delivers
    it through `onNewEndpoint` on EVERY app start, not once — so without this lookup one cold
    start per day silently adds a row, and one episode then yields N notifications on one device.
    The user's only remedy would be deleting rows they never knowingly created (decision A-O).

    Scoped by (transport, target) and NOT by user_id, matching the unique constraint exactly. A
    user-scoped lookup would miss another account's row, fall through to the insert, and hit that
    constraint as a 500 instead of the 409 below.

    NOT a Core `ON CONFLICT DO NOTHING` upsert, which is the usual answer to a check-then-insert
    race. Architecture rule 8 is why: a Core write does not invalidate this session's identity
    map, so the returned row would have to be re-read with populate_existing to be trusted, and
    the three outcomes here (created / already yours / someone else's) are not expressible as one
    conflict action anyway. The residual race is two simultaneous registrations of the same
    endpoint, which resolves as an IntegrityError on the loser — the constraint still holds the
    line, which is the property that matters.
    """
    # Origin-checked FIRST, ahead of the lookup, and the order is the point (decision A-L). Check
    # after the lookup and an endpoint that was legal when it was stored — but is off-server now,
    # because NTFY_BASE_URL moved — takes the `return existing, False` path and is re-blessed with
    # a 200. Here rather than in the route so there is ONE gate: a second caller cannot reach the
    # insert without passing it, and the thing being prevented is a stored SSRF target that the
    # dispatcher will later POST the ntfy credential to.
    validate_endpoint(endpoint)

    existing = await session.scalar(
        select(PushTarget).where(PushTarget.transport == PushTransport.UNIFIEDPUSH, PushTarget.target == endpoint)
    )
    if existing is not None:
        if existing.user_id != user_id:
            raise TargetOwnedByAnotherUser
        return existing, False

    target = PushTarget(
        user_id=user_id,
        transport=PushTransport.UNIFIEDPUSH,
        target=endpoint,
        label=label,
    )
    session.add(target)
    await session.flush()
    return target, True


async def list_targets(session: AsyncSession, *, user_id: uuid.UUID) -> list[PushTarget]:
    """Unpaginated, a deliberate exception to architecture rule 4. Nothing caps how many targets
    a user can register — this is not a schema-enforced bound. The exception is taken because
    ShowTrack is a personal, invite-gated deployment where the per-user device count is expected
    to stay small by convention, not because anything here prevents it from growing.
    """
    rows = await session.scalars(
        select(PushTarget).where(PushTarget.user_id == user_id).order_by(PushTarget.created_at)
    )
    return list(rows)


async def delete_target(session: AsyncSession, *, user_id: uuid.UUID, target_id: uuid.UUID) -> bool:
    """False when it does not exist OR is not this user's — the caller turns both into 404.

    Scoping the DELETE by user_id in the same statement, rather than fetching then checking, is
    what makes "not yours" and "not there" indistinguishable from outside. Confirming that an id
    exists but belongs to someone else is itself a disclosure.
    """
    result = await session.execute(delete(PushTarget).where(PushTarget.id == target_id, PushTarget.user_id == user_id))
    await session.flush()
    return result.rowcount > 0


# Module constants rather than settings, for the same reason as SYNC_TIERS: they interact, and
# independently-settable env vars could be combined incoherently.
MAX_ATTEMPTS = 5
BACKOFF_BASE = timedelta(minutes=2)
DISPATCH_BATCH_SIZE = 100


def _backoff(attempts: int) -> timedelta:
    """Exponential from the attempt just taken: 2m, 4m, 8m, 16m."""
    return BACKOFF_BASE * (2 ** max(attempts - 1, 0))


def _verdict(
    task: NotificationTask, media: Media | None, tracked: bool, push_enabled: bool, now: datetime
) -> NotificationTaskStatus | None:
    """None means "send it". Otherwise the terminal status to write (6-E).

    Order matters. User intent is checked first: someone who turned push off thirty seconds ago
    must not receive a queued notification, regardless of what the episode is doing.
    """
    if not tracked or not push_enabled:
        return NotificationTaskStatus.SKIPPED

    still_current = (
        media is not None
        and media.next_episode_number == task.episode_number
        and media.next_episode_date is not None
        # airs_on_for, NOT a raw timestamp comparison. AniList revises airingAt by seconds for
        # ordinary corrections; comparing instants would skip nearly everything.
        and airs_on_for(media.next_episode_date) == task.airs_on
    )
    if still_current:
        # The pointer still describes this episode, so its precise air time is trustworthy.
        return None if media.next_episode_date > now else NotificationTaskStatus.EXPIRED

    # The pointer moved on. Whether the episode AIRED is knowable from the pointer having advanced
    # PAST it — a reschedule keeps the episode number and moves the date, an airing advances the
    # number. Comparing dates cannot tell those apart: both sides are date-truncated, and backoff
    # totals ~30 minutes, so a genuinely late task is almost always same-day.
    if media is None:
        return NotificationTaskStatus.SKIPPED
    if media.next_episode_number is None:
        # A cleared pointer means EXPIRED, not "unknown". scan_thresholds cannot enqueue a task
        # while the number is NULL, so a NULL observed HERE means it was cleared after this task
        # was created: the episode aired and there is nothing after it, or the provider
        # transiently dropped the pointer. Both are possible because app/sync/service.py's _apply
        # writes `episode.number if episode else None`, so an AniList `nextAiringEpisode: null`
        # during a mid-season break clears the column exactly like a finale does. The finale shape
        # is the common one and the one this bucket is named for; the break case mis-buckets
        # EXPIRED where SKIPPED is right, and — like the rollover below — never causes or
        # suppresses a send.
        return NotificationTaskStatus.EXPIRED
    # KNOWN INCOMPLETE — season rollover. TMDB's episode_number is WITHIN-season, so a finale to
    # premiere goes (S1,12) -> (S2,1) and 1 > 12 is False: an aired finale reads as SKIPPED. Fixing
    # it needs the season in the comparison, and notification_tasks stores no season — a schema
    # change, a migration, and a dedup-key question. Deferred deliberately; it mis-buckets one
    # terminal status as another and never causes or suppresses a send.
    if media.next_episode_number > task.episode_number:
        return NotificationTaskStatus.EXPIRED
    return NotificationTaskStatus.SKIPPED


async def dispatch_once(
    session: AsyncSession,
    transports: Mapping[PushTransport, NotificationTransport],
    *,
    now: datetime,
) -> DispatchSummary:
    """Claim, re-validate, send, finalize.

    Commits ONCE in the middle, to make the attempt durable before the transport is touched (6-G);
    the caller commits the finalize half.

    Split out from run_dispatch so it is callable with a test's savepoint-joined session —
    run_dispatch owns its own sessions and would bypass the fixture entirely, the hazard
    tests/test_sync_job.py's `_run` helper documents at length.

    A MAPPING, not one transport, and this is decision A-P rather than a generalisation for its
    own sake. `send()` receives only the target STRING, so a routing wrapper standing in for a
    single transport could not tell a 43-character ntfy topic from a UnifiedPush callback URL —
    the one piece of information that decides the wire format lives on the target ROW, and this
    is the only place that has it. The alternative, sniffing `target.startswith("http")`, makes
    the routing decision a string-shape coincidence.
    """
    candidates = (
        select(NotificationTask, Media, UserMedia.user_id.label("tracked"), NotificationPrefs.push_enabled)
        .join(Media, NotificationTask.media_id == Media.id)
        # OUTER joins, deliberately. An inner join would silently EXCLUDE a task whose title was
        # untracked or whose prefs row vanished, leaving it `pending` forever and invisible in
        # every summary. We need those rows in order to mark them `skipped` and be done.
        .outerjoin(
            UserMedia,
            (UserMedia.user_id == NotificationTask.user_id) & (UserMedia.media_id == NotificationTask.media_id),
        )
        .outerjoin(NotificationPrefs, NotificationPrefs.user_id == NotificationTask.user_id)
        .where(NotificationTask.status == NotificationTaskStatus.PENDING)
        .where(NotificationTask.attempts < MAX_ATTEMPTS)
        .where(or_(NotificationTask.next_attempt_at.is_(None), NotificationTask.next_attempt_at <= now))
        .order_by(NotificationTask.created_at)
        .limit(DISPATCH_BATCH_SIZE)
    )
    rows = (await session.execute(candidates)).all()
    summary = DispatchSummary(ran=True, claimed=len(rows))
    if not rows:
        return summary

    if len(rows) == DISPATCH_BATCH_SIZE:
        # The batch was full, so there may be more due work. Count it and report it — a capped
        # run must not be indistinguishable from a completed one in the log ("no silent caps").
        # Only counted when the cap was actually hit, so the common case pays nothing.
        summary.remaining = await session.scalar(
            select(func.count())
            .select_from(NotificationTask)
            .where(NotificationTask.status == NotificationTaskStatus.PENDING)
            .where(NotificationTask.attempts < MAX_ATTEMPTS)
            .where(or_(NotificationTask.next_attempt_at.is_(None), NotificationTask.next_attempt_at <= now))
        ) - len(rows)

    targets_by_user: dict[uuid.UUID, list[PushTarget]] = defaultdict(list)
    unaddressable = 0
    for target in await session.scalars(
        select(PushTarget).where(PushTarget.user_id.in_({row.NotificationTask.user_id for row in rows}))
    ):
        if target.transport not in transports:
            # SKIPPED AND COUNTED, never an exception, and filtered out HERE rather than in the
            # send loop below — where it would first have burned an attempt on a target that
            # cannot be addressed at all, and reported `retrying` for something that will never
            # be retryable. Downstream this collapses into the existing "no targets registered"
            # branch, which already marks the task SKIPPED: nowhere to send is not a failure
            # (6-F).
            #
            # Defensive rather than operational: registry.get_transports() gates both transports
            # on NTFY_BASE_URL, so in this deployment they are configured together or not at all,
            # and with neither configured the dispatch job is not registered. Worth stating
            # because marking SKIPPED is TERMINAL — on_conflict_do_nothing ignores status, so the
            # dedup key can never be re-enqueued. If a future transport is ever gated on its own
            # setting, turning that setting off for an hour permanently drops every queued
            # notification for it, and this comment is where to start.
            unaddressable += 1
            continue
        targets_by_user[target.user_id].append(target)
    if unaddressable:
        logger.warning("%s push target(s) have no configured transport and were skipped", unaddressable)

    sendable: list[tuple[NotificationTask, list[PushTarget], PushMessage]] = []
    for row in rows:
        task = row.NotificationTask
        verdict = _verdict(task, row.Media, row.tracked is not None, bool(row.push_enabled), now)
        if verdict is None and not targets_by_user[task.user_id]:
            # Push is on but no device is registered. Nowhere to send is not a failure.
            #
            # A SAFETY NET, no longer the primary guard: scan_thresholds now refuses to enqueue
            # for a user with no target at all, because burning the dedup key to `skipped` is
            # permanent — on_conflict_do_nothing ignores status, so that key can never be
            # re-enqueued. What is left for this branch is the narrow race it is actually right
            # for: the user's last target was DELETED between enqueue and dispatch. Keep it.
            verdict = NotificationTaskStatus.SKIPPED
        if verdict is not None:
            task.status = verdict
            # Explicit, not setattr(summary, verdict.value, ...). That coupled the enum's VALUE
            # strings to the schema's FIELD names invisibly: renaming either broke exactly one
            # branch, at runtime, with an AttributeError, and turned up in no grep.
            if verdict is NotificationTaskStatus.EXPIRED:
                summary.expired += 1
            else:
                summary.skipped += 1
            continue

        # 6-G: the attempt is recorded and committed BEFORE the transport is touched, so a crash
        # mid-send retries. At-least-once, chosen because a duplicate push is annoying and a
        # missing one is indistinguishable from "no episode".
        task.attempts += 1
        task.next_attempt_at = now + _backoff(task.attempts)
        sendable.append(
            (
                task,
                targets_by_user[task.user_id],
                PushMessage(
                    title=row.Media.title,
                    body=f"Episode {task.episode_number} airs soon",
                    media_id=task.media_id,
                    episode_number=task.episode_number,
                    threshold=task.threshold,
                ),
            )
        )
    # COMMIT, not just flush, and this is the whole of 6-G rather than a durability nicety. A
    # flush is undone by the rollback that a crash implies, so a crash-looping process would
    # re-claim the same task at attempts=0 every minute: MAX_ATTEMPTS unreachable, the `failed`
    # bucket never written, and up to a full threshold-window of duplicate pushes where the spec
    # promises five. Safe under the savepoint-joined test fixture — join_transaction_mode
    # "create_savepoint" turns this into RELEASE SAVEPOINT, so nothing escapes the test's outer
    # transaction (measured: the full suite passes with zero leaked rows). Only the OTHER half of
    # the 4-M deviation — splitting claim/send/finalize across separate sessions — would break it.
    #
    # Safe to sit MID-FUNCTION only because the caller's session sets expire_on_commit=False (both
    # get_sessionmaker and the test fixture do). Under SQLAlchemy's default the commit would expire
    # every loaded object, and the send loop's `target.target` / `task.attempts` reads below would
    # become lazy reloads — which raise MissingGreenlet in async code rather than quietly
    # re-querying. A caller passing an expiring session breaks the loop, not the durability.
    await session.commit()

    for task, targets, message in sendable:
        delivered = False
        for target in targets:
            try:
                # Per ROW. Two targets on one task can take two different wire formats — which is
                # exactly the state a user has mid-migration, with an old ntfy topic and a new
                # UnifiedPush endpoint on the same phone.
                await transports[target.transport].send(target.target, message)
            except TransportPermanent:
                # Never succeeds. Prune rather than burn the attempt budget of live targets.
                # No target value in the log line — it is a bearer secret (6-L).
                logger.info("pruning a dead %s target for user %s", target.transport, task.user_id)
                await session.delete(target)
            except TransportRetryable:
                logger.warning("transport declined a push for task %s; will retry", task.id)
            else:
                delivered = True
                target.last_seen_at = now

        if delivered:
            task.status = NotificationTaskStatus.SENT
            task.sent_at = now
            summary.sent += 1
        elif task.attempts >= MAX_ATTEMPTS:
            task.status = NotificationTaskStatus.FAILED
            summary.failed += 1
        else:
            summary.retrying += 1

    await session.flush()
    return summary


async def run_dispatch(
    transports: Mapping[PushTransport, NotificationTransport], *, now: datetime | None = None
) -> DispatchSummary:
    """The locked, session-owning entry point, mirroring run_sync and run_threshold_scan."""
    async with advisory_lock(DISPATCH_LOCK_KEY) as acquired:
        if not acquired:
            return DispatchSummary(ran=False)
        now = now or datetime.now(tz=UTC)
        async with get_sessionmaker()() as session:
            summary = await dispatch_once(session, transports, now=now)
            await session.commit()
            return summary
