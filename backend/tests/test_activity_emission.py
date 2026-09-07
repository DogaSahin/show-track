from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.library import service
from app.library.models import Activity, ActivityKind, UserMediaStatus
from tests.factories import make_media, make_user


async def _user_and_media(db_session):
    user, media = make_user(), make_media()
    db_session.add_all([user, media])
    await db_session.flush()
    return user, media


async def _rows(db_session) -> list[Activity]:
    return list(await db_session.scalars(select(Activity)))


async def test_adding_a_title_emits_one_added_row(db_session):
    user, media = await _user_and_media(db_session)

    await service.add_entry(db_session, user_id=user.id, media_id=media.id)

    rows = await _rows(db_session)
    assert len(rows) == 1
    assert rows[0].kind is ActivityKind.ADDED
    assert rows[0].media_id == media.id


async def test_re_adding_a_tracked_title_emits_nothing(db_session):
    """add_entry returns (entry, created). Adding a title you already track is a no-op, and the
    feed must not claim otherwise."""
    user, media = await _user_and_media(db_session)
    await service.add_entry(db_session, user_id=user.id, media_id=media.id)

    await service.add_entry(db_session, user_id=user.id, media_id=media.id)

    assert len(await _rows(db_session)) == 1


async def test_a_multi_field_patch_emits_exactly_one_row(db_session):
    """Decision S-B. Three rows would share a byte-identical created_at and sort by random uuid."""
    user, media = await _user_and_media(db_session)
    entry, _ = await service.add_entry(db_session, user_id=user.id, media_id=media.id)
    before = len(await _rows(db_session))

    await service.update_entry(
        db_session,
        entry,
        {"status": UserMediaStatus.COMPLETED, "score": Decimal("9.0"), "progress": 12},
    )

    rows = await _rows(db_session)
    assert len(rows) == before + 1
    new = rows[-1]
    assert new.kind is ActivityKind.COMPLETED
    assert new.payload == {"status": "completed", "score": "9.0", "progress": 12}


async def test_a_patch_that_is_not_feed_worthy_emits_nothing(db_session):
    user, media = await _user_and_media(db_session)
    entry, _ = await service.add_entry(db_session, user_id=user.id, media_id=media.id)
    before = len(await _rows(db_session))

    await service.update_entry(db_session, entry, {"favorite": True})

    assert len(await _rows(db_session)) == before


async def test_an_import_emits_one_summary_row_not_one_per_title(db_session):
    """Decision S-A. The importer is bounded at 10,000 entries; one row per title would bury the
    feed permanently, and read-fanout leaves nothing to prune."""
    user = make_user()
    db_session.add(user)
    await db_session.flush()
    media = [make_media(external_id=str(n), title=f"title {n}") for n in range(3)]
    db_session.add_all(media)
    await db_session.flush()

    await service.bulk_add_entries(
        db_session,
        user_id=user.id,
        rows=[{"media_id": m.id, "status": UserMediaStatus.PLANNED} for m in media],
    )

    rows = await _rows(db_session)
    assert len(rows) == 1
    assert rows[0].kind is ActivityKind.IMPORTED
    assert rows[0].media_id is None
    assert rows[0].payload == {"count": 3}


async def test_an_import_that_inserts_nothing_emits_nothing(db_session):
    """Re-importing is idempotent by design ("local wins"), and the feed must not announce
    "imported 0 titles"."""
    user = make_user()
    db_session.add(user)
    await db_session.flush()
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    rows = [{"media_id": media.id, "status": UserMediaStatus.PLANNED}]
    await service.bulk_add_entries(db_session, user_id=user.id, rows=rows)
    before = len(await _rows(db_session))

    await service.bulk_add_entries(db_session, user_id=user.id, rows=rows)

    assert len(await _rows(db_session)) == before


@pytest.mark.parametrize(
    ("changes", "expected"),
    [
        ({"progress": 5}, ActivityKind.PROGRESSED),
        ({"score": Decimal("8.0")}, ActivityKind.RATED),
        ({"status": UserMediaStatus.COMPLETED}, ActivityKind.COMPLETED),
        ({"status": UserMediaStatus.DROPPED}, ActivityKind.DROPPED),
    ],
)
async def test_every_update_kind_reaches_the_database(changes, expected, db_session):
    """The pure tests pin the mapping; this pins that the wiring actually writes each one. Covers
    the four update-derived kinds — `added` and `imported` have their own tests above."""
    user, media = await _user_and_media(db_session)
    entry, _ = await service.add_entry(db_session, user_id=user.id, media_id=media.id)

    await service.update_entry(db_session, entry, changes)

    assert (await _rows(db_session))[-1].kind is expected


async def test_a_failed_library_mutation_writes_no_activity(db_session):
    """Task 7.5.3's own acceptance criterion, and the entire reason emission lives inside the
    service (S-E) rather than in a route.

    `user_media` carries a `score_range` CHECK rejecting anything outside 1.0-10.0, so a score of
    99 makes the flush fail. The activity row must not survive that.

    The `begin_nested()` wrapper scopes the unwind to a SAVEPOINT so the setup rows survive and
    the assertion below can still run — without it the abort would take the fixture's own data
    with it and the count would be 0 for the wrong reason. This is the technique 7.5a verified
    against SQLAlchemy 2.0.51.
    """
    user, media = await _user_and_media(db_session)
    entry, _ = await service.add_entry(db_session, user_id=user.id, media_id=media.id)
    before = len(await _rows(db_session))

    with pytest.raises(IntegrityError):
        async with db_session.begin_nested():
            await service.update_entry(db_session, entry, {"score": Decimal("99.0")})

    assert len(await _rows(db_session)) == before


async def test_a_rolled_back_library_mutation_leaves_no_activity(db_session):
    """The other half of S-E: emission flushes, never commits.

    The test above proves no row survives a mutation that FAILS — but on that path the UPDATE's
    own flush raises before emission is ever reached, so it cannot tell a flush from a commit.
    Mutation-testing confirmed it: `_emit` committing instead of flushing leaves it green.

    This one runs a mutation that SUCCEEDS and then unwinds it. If `_emit` committed, the row
    would already have escaped the caller's transaction and the rollback would find nothing left
    to undo — which is exactly what makes the activity row and the library change it describes
    inseparable by construction rather than by discipline.
    """
    user, media = await _user_and_media(db_session)

    nested = await db_session.begin_nested()
    await service.add_entry(db_session, user_id=user.id, media_id=media.id)
    assert db_session.in_nested_transaction(), "the service committed the caller's transaction"
    await nested.rollback()

    assert await _rows(db_session) == []


async def test_removing_a_title_emits_nothing(db_session):
    """Decision S-F: there is no `removed` kind. Removal is the one library action that is not a
    statement about taste."""
    user, media = await _user_and_media(db_session)
    entry, _ = await service.add_entry(db_session, user_id=user.id, media_id=media.id)
    before = len(await _rows(db_session))

    await service.delete_entry(db_session, entry)

    assert len(await _rows(db_session)) == before
