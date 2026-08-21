import secrets
import uuid

from sqlalchemy import delete, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.notifications.models import NotificationPrefs, PushTarget, PushTransport

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
