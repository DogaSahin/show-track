from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.library.models import UserMedia, UserMediaStatus
from tests.factories import make_parents, make_user_media


async def test_a_paused_entry_round_trips(db_session: AsyncSession) -> None:
    """`paused` is the fifth status added after the original spec — this is the test that
    proves the enum actually carries it."""
    user, media = await make_parents(db_session)
    entry = make_user_media(user.id, media.id, status=UserMediaStatus.PAUSED)
    db_session.add(entry)
    await db_session.flush()
    entry_id = entry.id
    db_session.expire(entry)

    reloaded = (await db_session.execute(select(UserMedia).where(UserMedia.id == entry_id))).scalar_one()
    assert reloaded.status is UserMediaStatus.PAUSED


async def test_a_score_of_seven_point_one_reads_back_exactly(db_session: AsyncSession) -> None:
    """The whole reason the column is NUMERIC(3,1). As a float this comparison drifts."""
    user, media = await make_parents(db_session)
    entry = make_user_media(user.id, media.id, score=Decimal("7.1"))
    db_session.add(entry)
    await db_session.flush()
    entry_id = entry.id

    stored = (await db_session.execute(select(UserMedia.score).where(UserMedia.id == entry_id))).scalar_one()
    assert stored == Decimal("7.1")


async def test_an_out_of_range_score_is_rejected(db_session: AsyncSession) -> None:
    """85 is not arbitrary: it is what an AniList POINT_100 score looks like if the Phase 4
    importer forgets to convert it to the 1-10 scale."""
    user, media = await make_parents(db_session)
    db_session.add(make_user_media(user.id, media.id, score=Decimal("85.0")))

    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "ck_user_media_score_range" in str(excinfo.value)
    await db_session.rollback()


async def test_a_null_score_is_allowed(db_session: AsyncSession) -> None:
    """The CHECK must not accidentally make score mandatory — an unrated entry is normal."""
    user, media = await make_parents(db_session)
    entry = make_user_media(user.id, media.id, score=None)
    db_session.add(entry)
    await db_session.flush()

    assert entry.score is None
    assert entry.progress == 0
    assert entry.favorite is False


async def test_the_same_title_cannot_be_added_twice_by_one_user(db_session: AsyncSession) -> None:
    user, media = await make_parents(db_session)
    db_session.add(make_user_media(user.id, media.id))
    await db_session.flush()

    db_session.add(make_user_media(user.id, media.id))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_user_media_user_id_media_id" in str(excinfo.value)
    await db_session.rollback()
