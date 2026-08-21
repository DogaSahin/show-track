import uuid

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.notifications.models import NotificationPrefs


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
