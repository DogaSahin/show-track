import sys
import uuid
from datetime import UTC, datetime

import pytest
from sqlalchemy import func, select

from app.groups import service
from app.groups.models import Group, GroupRole, GroupWatchlist
from app.media.models import Media
from app.pagination import encode_cursor
from tests.factories import (
    make_group,
    make_group_member,
    make_media,
    make_user,
    make_watchlist_entry,
)


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
    reason it is written down, and it stops being right the moment the delete stops synchronizing
    the map. `synchronize_session=False` on that same Core `delete()` is the trap in its next
    disguise — measured: it leaves the object in the map, so `session.get` hands back a row the
    table no longer holds, while this count correctly reads 0. A DB-side cascade and a statement
    run on the raw connection do the same. A count has no such dependency and costs nothing.
    """
    statement = select(func.count()).select_from(GroupWatchlist).where(GroupWatchlist.id == entry_id)
    return await db_session.scalar(statement)


async def test_proposing_a_title(auth_client, auth_user, db_session, commits):
    group = await _my_group(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()

    marker = len(commits)
    response = await auth_client.post(f"/v1/groups/{group.id}/watchlist", json={"media_id": str(media.id)})

    assert response.status_code == 200
    assert response.json()["proposed_by"] == str(auth_user.id)
    assert response.json()["media"]["id"] == str(media.id)
    # Counted from a marker, not `assert commits` — the auth fixture and any earlier request in
    # the same test commit too. See the `commits` fixture for why nothing else can see this.
    assert len(commits) > marker, "POST /v1/groups/{id}/watchlist did not commit"


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

    real_find = service._find_entry

    async def blind_the_dedupe(session, *, group_id, media_id):
        """Stand in for a row another transaction commits between the dedupe SELECT and the
        flush: invisible to the dedupe lookup, found by the recovery one.

        The two call sites are identical in every session-state dimension — same arguments, same
        `in_nested_transaction()`, same `session.new` and `session.dirty`, same identity map, same
        row in the table — with ONE exception: the recovery lookup runs inside the
        `except IntegrityError` block. `sys.exc_info()` is therefore not a proxy for the recovery
        path, it is that path's defining property, and blinding on it says exactly what this test
        means: the row was invisible until we collided with it.

        Deliberately not keyed on the call ORDINAL, which would work today and mislead tomorrow.
        Dropping the dedupe SELECT changes no answer this API gives, but it makes the first call
        the recovery one — an ordinal patch then fails this test over a behaviourally neutral
        change, pointing at the savepoint and the 23505 branch, neither of which moved.
        """
        if sys.exc_info()[0] is None:
            return None
        return await real_find(session, group_id=group_id, media_id=media_id)

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(service, "_find_entry", blind_the_dedupe)
        entry = await service.propose_title(db_session, group_id=group.id, media_id=media.id, user_id=auth_user.id)

    assert entry.id == winner.id, "the loser of the race gets the winner's row, not an error"
    # The session is still usable at all. A real statement, NOT flush(): nothing is dirty at this
    # point, so a flush short-circuits without touching the connection and would pass even on a
    # poisoned transaction.
    assert await db_session.scalar(select(1)) == 1
    assert await db_session.scalar(select(func.count()).select_from(Media).where(Media.id == pending.id)) == 1
    assert await _count(db_session, winner.id) == 1


async def test_any_member_may_remove_any_entry(auth_client, auth_user, db_session, commits):
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

    marker = len(commits)
    response = await auth_client.delete(f"/v1/groups/{group.id}/watchlist/{entry.id}")

    assert response.status_code == 204
    assert await _count(db_session, entry.id) == 0
    # Counted from a marker, not `assert commits` — the auth fixture and any earlier request in
    # the same test commit too. See the `commits` fixture for why nothing else can see this.
    assert len(commits) > marker, "DELETE /v1/groups/{id}/watchlist/{entry_id} did not commit"


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


async def test_both_read_paths_are_scoped_to_the_group_in_the_path(auth_client, auth_user, db_session):
    """GroupMemberDep proves membership of the group in the PATH and says nothing about which
    group's ROWS come back. Two separate `WHERE group_id` clauses carry that, and neither was
    pinned — the DELETE path got its own scoping test and the two read paths did not.

    Drop it from `list_watchlist` and GET /v1/groups/{mine}/watchlist serves another group's
    titles and another group's proposer ids to somebody with no relationship to it. Drop it from
    `_find_entry` and proposing a title the other group already lists answers 200 carrying THEIR
    entry id and THEIR proposer, while this group's list never gains the row — a silent no-op on
    top of the leak.

    The body assertions are load-bearing, not decoration: `media.id` and `proposed_by` are
    otherwise unasserted anywhere in this file, so a `proposed_by=row.GroupWatchlist.id` mix-up
    would serialise as a perfectly valid UUID and sail through.
    """
    mine = await _my_group(db_session, auth_user)
    stranger = make_user(username="stranger", email="stranger@example.com")
    db_session.add(stranger)
    await db_session.flush()
    theirs = make_group(created_by=stranger.id, invite_code="ZZZZZZZZ9999")
    shared = make_media(external_id="20", title="Naruto")
    only_theirs = make_media(external_id="21", title="Bleach")
    db_session.add_all([theirs, shared, only_theirs])
    await db_session.flush()
    db_session.add_all(
        [
            make_watchlist_entry(theirs.id, shared.id, proposed_by=stranger.id),
            make_watchlist_entry(theirs.id, only_theirs.id, proposed_by=stranger.id),
        ]
    )
    await db_session.flush()

    proposed = await auth_client.post(f"/v1/groups/{mine.id}/watchlist", json={"media_id": str(shared.id)})
    # A cold session for the LIST path, the same pin the feed carries. `list_watchlist`'s explicit
    # join on Media is load-bearing: a lazy many-to-one on the target's PRIMARY KEY takes
    # SQLAlchemy's `load_on_pk_identity` shortcut and raises MissingGreenlet — a production 500 —
    # the moment the row is not already in the map, which the POST above has just put it back into.
    # Measured: a relationship-based list_watchlist passes every OTHER test in this file. Drift
    # protection, not a live bug. It works only because the join loads `media` rows; get_current_user
    # re-SELECTs the bearer token's USER back into the map mid-request, so a join on that row would
    # silently stop proving anything.
    db_session.expunge_all()
    listed = (await auth_client.get(f"/v1/groups/{mine.id}/watchlist")).json()

    assert proposed.status_code == 200
    assert proposed.json()["proposed_by"] == str(auth_user.id), "answered with the other group's entry"
    # A NEW row for THIS group, not a 200 over the other group's.
    mine_rows = select(func.count()).select_from(GroupWatchlist).where(GroupWatchlist.group_id == mine.id)
    assert await db_session.scalar(mine_rows) == 1
    assert [item["media"]["id"] for item in listed["items"]] == [str(shared.id)]
    assert listed["items"][0]["id"] == proposed.json()["id"]
    assert listed["items"][0]["proposed_by"] == str(auth_user.id)


async def test_the_shared_watchlist_dies_with_the_group(auth_client, auth_user, db_session):
    """The README's most prominent lifecycle claim, and its only destructive one: "The shared
    watchlist dies with the group, without a confirmation step."

    `remove_member` is 7.5a code that this phase silently made destructive — G-E deletes the group
    outright when the last member walks out, and `group_watchlist.group_id` carries
    ON DELETE CASCADE. Nothing in the suite deleted a group holding watchlist rows, so the claim
    was documentation only.

    ARCHITECTURE RULE 8. Asserted through a Core `select(func.count())`, never `session.get()`:
    a DB-side cascade is not this session's own ORM write, so it does NOT evict the cascaded row
    from the identity map. Measured for this project — `session.delete` + flush and an ORM-enabled
    Core `delete()` both evict and are safe; `synchronize_session=False`, a raw connection DELETE,
    and a DB-side cascade all leave a stale object that `session.get` hands straight back with no
    query at all. The identity-map instrument is a guaranteed false green here.
    """
    group = await _my_group(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    entry = make_watchlist_entry(group.id, media.id, proposed_by=auth_user.id)
    db_session.add(entry)
    await db_session.flush()
    entry_id = entry.id

    # The only member removes themselves, so there is no successor to promote.
    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{auth_user.id}")

    assert response.status_code == 204
    groups_left = select(func.count()).select_from(Group).where(Group.id == group.id)
    assert await db_session.scalar(groups_left) == 0, "the last member leaving must delete the group (G-E)"
    assert await _count(db_session, entry_id) == 0, "the cascade did not take the shared list with it"


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


async def test_a_feed_cursor_is_rejected_by_the_watchlist(auth_client, auth_user, db_session):
    """The sort key rides INSIDE the cursor so that replaying one under a different sort is
    detectable — and that guard is only as strong as the two keys being distinct. Both endpoints
    order by `created_at`, so while the watchlist's key was spelled `"created_at"` a feed cursor
    decoded cleanly here and repositioned the caller in a window that means nothing. Nothing
    leaked (a cursor is unsigned and opaque by design, and both endpoints are gated on the same
    group), but the guard read stronger than it was.
    """
    group = await _my_group(db_session, auth_user)
    borrowed = encode_cursor(service.FEED_SORT_KEY, datetime.now(tz=UTC), uuid.uuid4())

    response = await auth_client.get(f"/v1/groups/{group.id}/watchlist?cursor={borrowed}")

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid cursor"


async def test_an_unusable_cursor_is_a_400(auth_client, auth_user, db_session):
    """Client-supplied bytes reaching a decoder: a 500 here would be a crash on ordinary bad
    input. The detail is fixed, never str(exc), so the response cannot echo what was sent."""
    group = await _my_group(db_session, auth_user)

    response = await auth_client.get(f"/v1/groups/{group.id}/watchlist?cursor=not-a-cursor")

    assert response.status_code == 400
    assert response.json()["detail"] == "invalid cursor"
