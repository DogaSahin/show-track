import uuid

import pytest
from sqlalchemy import func, select

from app.groups import service
from app.groups.models import GroupRole, GroupWatchlist
from app.media.models import Media
from tests.factories import make_group, make_group_member, make_media, make_user


async def _my_group(db_session, auth_user):
    group = make_group(created_by=auth_user.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, auth_user.id, role=GroupRole.OWNER))
    await db_session.flush()
    return group


async def _count(db_session, entry_id):
    """What these tests mean is "the table holds this row", so they ask the table.

    Architecture rule 8 makes `session.get()` the wrong instrument for that in general: it
    answers from the identity map, which nothing but this session's own ORM writes invalidates.
    MEASURED here, so the next reader does not over-read the rule — the identity-map form
    discriminates too, because BOTH the ORM `session.delete` this service uses and an
    ORM-enabled Core `delete()` synchronize the map. It is right for a reason that is not the
    reason it is written down, and it stops being right the moment the delete leaves the ORM
    session (a DB-side cascade, a statement run on the connection). A count has no such
    dependency and costs nothing.
    """
    statement = select(func.count()).select_from(GroupWatchlist).where(GroupWatchlist.id == entry_id)
    return await db_session.scalar(statement)


async def test_proposing_a_title(auth_client, auth_user, db_session):
    group = await _my_group(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()

    response = await auth_client.post(f"/v1/groups/{group.id}/watchlist", json={"media_id": str(media.id)})

    assert response.status_code == 200
    assert response.json()["proposed_by"] == str(auth_user.id)
    assert response.json()["media"]["id"] == str(media.id)


async def test_proposing_the_same_title_twice_is_idempotent(auth_client, auth_user, db_session):
    """Decision S-I, matching 4-D and 7.5a's idempotent join: two housemates proposing the same
    show is agreement, not a conflict. This deviates from 7.5.6's acceptance criterion."""
    group = await _my_group(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    body = {"media_id": str(media.id)}
    await auth_client.post(f"/v1/groups/{group.id}/watchlist", json=body)

    again = await auth_client.post(f"/v1/groups/{group.id}/watchlist", json=body)

    assert again.status_code == 200
    rows = list(await db_session.scalars(select(GroupWatchlist).where(GroupWatchlist.group_id == group.id)))
    assert len(rows) == 1


async def test_proposing_a_title_that_does_not_exist_is_a_404(auth_client, auth_user, db_session):
    """`group_watchlist.media_id` is an FK and the media_id is CLIENT-SUPPLIED, so an unknown one
    raises IntegrityError from the same flush a duplicate does. Reading every IntegrityError as
    the unique constraint sends the recovery SELECT looking for a row that was never written, it
    returns None, and the route then evaluates `entry.id` on None — a 500 on ordinary bad input.
    Same discrimination Task 4 added to `create_review`, same reason.
    """
    group = await _my_group(db_session, auth_user)

    response = await auth_client.post(f"/v1/groups/{group.id}/watchlist", json={"media_id": str(uuid.uuid4())})

    assert response.status_code == 404, "an unknown media_id must not be reported as a successful proposal"
    assert response.json()["detail"] == "no such title"


async def test_a_lost_race_returns_the_winner_and_spares_the_callers_pending_work(auth_user, db_session):
    """The 23505 branch and the SAVEPOINT, which no route-level test can reach.

    The dedupe SELECT means a second proposal never normally reaches the INSERT, so the recovery
    path only runs when another transaction commits between that SELECT and this flush. A test
    transaction cannot produce that race — every connection it could open is outside the
    transaction the group itself was created in, so the conflicting row could not satisfy its own
    foreign key. Making the lookup miss ONCE is the same situation from this function's point of
    view, and it is the only way to stand in it.

    What the savepoint buys is that the failed INSERT is contained instead of unwinding
    everything the caller had pending. With `session.add` OUTSIDE the nested block the pending
    entry is part of the snapshot the savepoint was opened on, so rolling back neither expunges
    it nor confines the exception, and the very next statement raises PendingRollbackError.
    """
    group = await _my_group(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    winner = await service.propose_title(db_session, group_id=group.id, media_id=media.id, user_id=auth_user.id)

    # The caller's unrelated, pending, uncommitted work. This is what must survive.
    pending = make_media(external_id="1535", title="Death Note")
    db_session.add(pending)
    await db_session.flush()

    seen = {"lookups": 0}
    real_find = service._find_entry

    async def blind_once(session, *, group_id, media_id):
        seen["lookups"] += 1
        if seen["lookups"] == 1:
            return None
        return await real_find(session, group_id=group_id, media_id=media_id)

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(service, "_find_entry", blind_once)
        entry = await service.propose_title(db_session, group_id=group.id, media_id=media.id, user_id=auth_user.id)

    assert entry.id == winner.id, "the loser of the race gets the winner's row, not an error"
    # The session is still usable at all. A real statement, NOT flush(): nothing is dirty at this
    # point, so a flush short-circuits without touching the connection and would pass even on a
    # poisoned transaction.
    assert await db_session.scalar(select(1)) == 1
    assert await db_session.scalar(select(func.count()).select_from(Media).where(Media.id == pending.id)) == 1
    assert await _count(db_session, winner.id) == 1


async def test_any_member_may_remove_any_entry(auth_client, auth_user, db_session):
    """Decision S-L: it is a shared list, and §5.3's whole moderation model is removing members."""
    group = await _my_group(db_session, auth_user)
    proposer = make_user(username="ada", email="ada@example.com")
    media = make_media()
    db_session.add_all([proposer, media])
    await db_session.flush()
    db_session.add(make_group_member(group.id, proposer.id, role=GroupRole.MEMBER))
    entry = GroupWatchlist(group_id=group.id, media_id=media.id, proposed_by=proposer.id)
    db_session.add(entry)
    await db_session.flush()

    response = await auth_client.delete(f"/v1/groups/{group.id}/watchlist/{entry.id}")

    assert response.status_code == 204
    assert await _count(db_session, entry.id) == 0


async def test_removing_an_entry_from_another_group_is_a_404(auth_client, auth_user, db_session):
    """The entry id must be scoped to the group in the path, or a member of one group could
    delete another group's entries by id."""
    mine = await _my_group(db_session, auth_user)
    stranger = make_user(username="stranger", email="stranger@example.com")
    db_session.add(stranger)
    await db_session.flush()
    theirs = make_group(created_by=stranger.id, invite_code="ZZZZZZZZ9999")
    media = make_media()
    db_session.add_all([theirs, media])
    await db_session.flush()
    entry = GroupWatchlist(group_id=theirs.id, media_id=media.id, proposed_by=stranger.id)
    db_session.add(entry)
    await db_session.flush()

    response = await auth_client.delete(f"/v1/groups/{mine.id}/watchlist/{entry.id}")

    assert response.status_code == 404
    assert await _count(db_session, entry.id) == 1


async def test_paging_the_watchlist_yields_every_row_exactly_once(auth_client, auth_user, db_session):
    """All five rows share a created_at — Postgres `now()` is transaction-start time and the test
    fixture runs the whole request sequence inside one transaction — so this is precisely the case
    the cursor's `id` half exists for. Without it the keyset predicate would skip or repeat rows
    at every page boundary.
    """
    group = await _my_group(db_session, auth_user)
    media = [make_media(external_id=str(n), title=f"title {n}") for n in range(5)]
    db_session.add_all(media)
    await db_session.flush()
    for m in media:
        await auth_client.post(f"/v1/groups/{group.id}/watchlist", json={"media_id": str(m.id)})

    first = (await auth_client.get(f"/v1/groups/{group.id}/watchlist?limit=2")).json()
    second = (await auth_client.get(f"/v1/groups/{group.id}/watchlist?limit=2&cursor={first['next_cursor']}")).json()
    third = (await auth_client.get(f"/v1/groups/{group.id}/watchlist?limit=2&cursor={second['next_cursor']}")).json()

    ids = [i["id"] for i in first["items"] + second["items"] + third["items"]]
    assert len(ids) == 5 and len(set(ids)) == 5
    assert third["next_cursor"] is None


async def test_an_unusable_cursor_is_a_400(auth_client, auth_user, db_session):
    """Client-supplied bytes reaching a decoder: a 500 here would be a crash on ordinary bad
    input. The detail is fixed, never str(exc), so the response cannot echo what was sent."""
    group = await _my_group(db_session, auth_user)

    response = await auth_client.get(f"/v1/groups/{group.id}/watchlist?cursor=not-a-cursor")

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid cursor"
