import pytest
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.media.models import Episode, Media, MediaStatus
from tests.factories import make_episode, make_media


async def test_duplicate_source_and_external_id_is_rejected(db_session: AsyncSession) -> None:
    """The constraint that stops the same title being imported twice from one provider."""
    db_session.add(make_media(external_id="16498"))
    await db_session.flush()

    db_session.add(make_media(external_id="16498"))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_media_source_external_id" in str(excinfo.value)
    await db_session.rollback()


async def test_genres_round_trip_as_a_list_of_strings(db_session: AsyncSession) -> None:
    media = make_media(genres=["Action", "Thriller"])
    db_session.add(media)
    await db_session.flush()
    media_id = media.id

    stored = (await db_session.execute(select(Media.genres).where(Media.id == media_id))).scalar_one()
    assert stored == ["Action", "Thriller"]


async def test_an_invalid_status_is_rejected_by_the_check_constraint(db_session: AsyncSession) -> None:
    """Guards the `create_constraint=True` trap.

    Raw SQL on purpose: the ORM rejects a bad enum member in Python, so an ORM insert would
    pass whether or not the database constraint exists. This is the only way to prove the
    column is actually constrained — with SQLAlchemy's default (`create_constraint=False`)
    this INSERT succeeds and `status` is an unguarded VARCHAR.
    """
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.execute(
            text(
                "INSERT INTO media (id, type, source, external_id, title, status) "
                "VALUES (gen_random_uuid(), 'anime', 'anilist', 'bad', 'Untitled', 'cancelled')"
            )
        )

    assert "ck_media_status" in str(excinfo.value)
    await db_session.rollback()


async def test_status_reads_back_as_an_enum_member(db_session: AsyncSession) -> None:
    """Proves `values_callable` stores the value and maps it back, rather than storing the
    member name and silently failing to round-trip."""
    media = make_media(status=MediaStatus.AIRING)
    db_session.add(media)
    await db_session.flush()
    media_id = media.id
    db_session.expire(media)

    reloaded = (await db_session.execute(select(Media).where(Media.id == media_id))).scalar_one()
    assert reloaded.status is MediaStatus.AIRING


async def test_deleting_media_cascades_to_its_episodes(db_session: AsyncSession) -> None:
    """FK cascade is a database behaviour, not an ORM one — there is no relationship() to
    do it in Python, so this proves the ON DELETE CASCADE really made it into the DDL."""
    media = make_media(external_id="cascade-me")
    db_session.add(media)
    await db_session.flush()
    db_session.add(make_episode(media.id, number=1))
    db_session.add(make_episode(media.id, number=2))
    await db_session.flush()

    before = (
        await db_session.execute(select(func.count()).select_from(Episode).where(Episode.media_id == media.id))
    ).scalar_one()
    assert before == 2

    await db_session.execute(delete(Media).where(Media.id == media.id))
    await db_session.flush()

    remaining = (
        await db_session.execute(select(func.count()).select_from(Episode).where(Episode.media_id == media.id))
    ).scalar_one()
    assert remaining == 0


async def test_duplicate_episode_within_a_season_is_rejected(db_session: AsyncSession) -> None:
    media = make_media(external_id="dupe-episode")
    db_session.add(media)
    await db_session.flush()

    db_session.add(make_episode(media.id, season_number=1, number=1))
    await db_session.flush()

    db_session.add(make_episode(media.id, season_number=1, number=1))
    with pytest.raises(IntegrityError) as excinfo:
        await db_session.flush()

    assert "uq_episodes_media_id_season_number_number" in str(excinfo.value)
    await db_session.rollback()


async def test_the_same_episode_number_is_allowed_in_a_different_season(db_session: AsyncSession) -> None:
    """The reason season_number exists: a TMDB show's S1E1 and S2E1 must coexist."""
    media = make_media(external_id="two-seasons")
    db_session.add(media)
    await db_session.flush()

    db_session.add(make_episode(media.id, season_number=1, number=1))
    db_session.add(make_episode(media.id, season_number=2, number=1))
    await db_session.flush()

    stored = (
        await db_session.execute(select(func.count()).select_from(Episode).where(Episode.media_id == media.id))
    ).scalar_one()
    assert stored == 2
