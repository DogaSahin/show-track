import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.library.models import ActivityKind
from tests.factories import (
    make_activity,
    make_group,
    make_media,
    make_review,
    make_user,
    make_watchlist_entry,
)


async def test_an_imported_activity_row_needs_no_media(db_session):
    """Decision S-A/S-D: an `imported` row is about N titles, so media_id must be nullable.
    If this column were NOT NULL the import summary could not be written at all."""
    user = make_user()
    db_session.add(user)
    await db_session.flush()

    db_session.add(make_activity(user.id, media_id=None, kind=ActivityKind.IMPORTED, payload={"count": 412}))
    await db_session.flush()


async def test_one_review_per_person_per_title(db_session):
    user, media = make_user(), make_media()
    db_session.add_all([user, media])
    await db_session.flush()
    db_session.add(make_review(user.id, media.id))
    await db_session.flush()

    db_session.add(make_review(user.id, media.id, body="second opinion"))
    with pytest.raises(IntegrityError):
        await db_session.flush()


async def test_the_same_title_cannot_be_proposed_twice_to_one_group(db_session):
    group, media = make_group(), make_media()
    db_session.add_all([group, media])
    await db_session.flush()
    db_session.add(make_watchlist_entry(group.id, media.id))
    await db_session.flush()

    db_session.add(make_watchlist_entry(group.id, media.id))
    with pytest.raises(IntegrityError):
        await db_session.flush()


async def test_the_same_title_in_two_groups_is_fine(db_session):
    a, b, media = make_group(invite_code="AAAAAAAA1111"), make_group(invite_code="BBBBBBBB2222"), make_media()
    db_session.add_all([a, b, media])
    await db_session.flush()

    db_session.add_all([make_watchlist_entry(a.id, media.id), make_watchlist_entry(b.id, media.id)])
    await db_session.flush()


async def test_deleting_the_proposer_leaves_the_entry_standing(db_session):
    """SET NULL, not CASCADE: deleting your account must not erase the list the group built."""
    user, group, media = make_user(), make_group(), make_media()
    db_session.add_all([user, group, media])
    await db_session.flush()
    entry = make_watchlist_entry(group.id, media.id, proposed_by=user.id)
    db_session.add(entry)
    await db_session.flush()

    await db_session.delete(user)
    await db_session.flush()
    await db_session.refresh(entry)

    assert entry.proposed_by is None


async def test_activity_payload_column_is_genuinely_jsonb(db_session):
    """A generic JSON column has no indexing or containment operators, and nothing downstream
    would notice the regression until it mattered. `alembic check` diffs Python types against
    reflected DDL and would catch a model-level regression, but not a hand-edited migration that
    silently substitutes `sa.JSON` for `postgresql.JSONB` while the model still says JSONB — so
    this asserts what actually landed in the catalog, not what the model claims."""
    row = (
        await db_session.execute(
            text(
                "SELECT data_type, udt_name FROM information_schema.columns "
                "WHERE table_name = 'activity' AND column_name = 'payload'"
            )
        )
    ).one()

    assert row.data_type == "jsonb"
    assert row.udt_name == "jsonb"


async def test_activity_feed_index_is_descending_on_created_at_and_id(db_session):
    """`ix_activity_user_id_created_at_id` is an expression index (DESC on two columns), and
    Alembic's Postgres reflection skips expression indexes when diffing autogenerate — `alembic
    check` is silent about this index entirely, so a future edit to its columns or direction
    would pass the gate unnoticed. This reads the index definition straight out of the catalog,
    the way the feed's cursor query depends on it actually being ordered."""
    row = (
        await db_session.execute(
            text("SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_activity_user_id_created_at_id'")
        )
    ).one()

    assert "created_at DESC" in row.indexdef
    assert "id DESC" in row.indexdef
