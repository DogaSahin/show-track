import pytest
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
