import uuid
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.library.models import UserMedia, UserMediaStatus
from app.library.schemas import LibraryEntry
from app.media import service as media_service
from app.media.models import Media


def to_entry(entry: UserMedia, media: Media, now: datetime) -> LibraryEntry:
    return LibraryEntry(
        id=entry.id,
        status=entry.status,
        score=entry.score,
        progress=entry.progress,
        favorite=entry.favorite,
        updated_at=entry.updated_at,
        media=media_service.to_detail(media, now),
    )


async def add_entry(session: AsyncSession, *, user_id: uuid.UUID, media_id: uuid.UUID) -> tuple[UserMedia, bool]:
    """Returns (entry, created). Never overwrites an existing entry.

    SELECT-then-upsert, not ON CONFLICT DO NOTHING ... RETURNING. DO NOTHING carries the same
    invisibility problem 4-A removed from `media`: it neither locks nor waits on a conflicting
    uncommitted row, and the fallback SELECT at READ COMMITTED cannot see one either, so the
    loser of a concurrent double-tap would get nothing back to return. DO UPDATE with a no-op SET
    always returns a row.

    Accepted imprecision: `created` is decided by the SELECT, so in a genuine race both callers
    report 201 while one of them actually resolved the other's row. That is a cosmetically wrong
    status code on a rare race with no data consequence. Deciding it correctly means
    RETURNING (xmax = 0), a system-column trick with edge cases this project cannot verify.
    """
    existing = await session.scalar(
        select(UserMedia).where(UserMedia.user_id == user_id, UserMedia.media_id == media_id)
    )
    if existing is not None:
        return existing, False

    statement = (
        pg_insert(UserMedia)
        .values(user_id=user_id, media_id=media_id, status=UserMediaStatus.PLANNED)
        .on_conflict_do_update(
            index_elements=["user_id", "media_id"],
            set_={"user_id": UserMedia.__table__.c.user_id},
        )
        .returning(UserMedia.id)
    )
    entry_id = await session.scalar(statement)
    # progress, favorite and updated_at come from their server defaults, so the row is read back
    # rather than assembled here.
    return await session.get(UserMedia, entry_id), True
