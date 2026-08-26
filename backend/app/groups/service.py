import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select, tuple_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.db import FOREIGN_KEY_VIOLATION, UNIQUE_VIOLATION
from app.groups import invites
from app.groups.models import Group, GroupMember, GroupRole, GroupWatchlist
from app.groups.schemas import FeedActor, FeedItem, ProgressEntry, WatchlistItem
from app.library.models import Activity, Review, UserMedia
from app.library.schemas import ReviewRead
from app.library.service import MediaMissing, to_review_read
from app.media.models import Media
from app.media.service import to_detail
from app.pagination import Cursor, encode_cursor
from app.users.models import User

# Bounded, not optimistic: at 60 bits a collision is not a probability worth reasoning about,
# so this exists to make the impossible case a retry rather than a 500.
_CODE_ATTEMPTS = 5


def _expiry(now: datetime) -> datetime:
    return now + timedelta(hours=get_settings().group_invite_ttl_hours)


async def _fresh_code(session: AsyncSession) -> str:
    for _ in range(_CODE_ATTEMPTS):
        code = invites.generate_code()
        if await session.scalar(select(Group.id).where(Group.invite_code == code)) is None:
            return code
    raise RuntimeError("could not allocate a unique invite code")


async def create_group(session: AsyncSession, *, name: str, owner: User, now: datetime) -> Group:
    """The creator is the owner. This and G-E's transfer are the ONLY ways to become one —
    no invite code can mint an owner, or a leaked code would hand over administrative control
    of the group rather than merely access to it.
    """
    group = Group(
        name=name,
        invite_code=await _fresh_code(session),
        invite_code_expires_at=_expiry(now),
        created_by=owner.id,
    )
    session.add(group)
    await session.flush()
    session.add(GroupMember(group_id=group.id, user_id=owner.id, role=GroupRole.OWNER))
    await session.flush()
    return group


async def resolve_invite_code(session: AsyncSession, code: str, *, now: datetime) -> Group | None:
    """None for unknown AND for expired — deliberately indistinguishable.

    An expired code that reported itself as expired would confirm the group exists and that
    you were merely too late, which is the oracle G-D closes everywhere else.

    Matched by EQUALITY on the normalised value, never a prefix or LIKE. normalise_code("")
    and normalise_code("---") both return "", and equality against a column whose every value
    is 12 characters can never match that; a prefix match would match everything.
    """
    group = await session.scalar(select(Group).where(Group.invite_code == invites.normalise_code(code)))
    if group is None or group.invite_code_expires_at <= now:
        return None
    return group


async def add_member(session: AsyncSession, *, group_id: uuid.UUID, user_id: uuid.UUID, role: GroupRole) -> GroupMember:
    member = GroupMember(group_id=group_id, user_id=user_id, role=role)
    session.add(member)
    await session.flush()
    return member


async def join_by_code(session: AsyncSession, *, code: str, user: User, now: datetime) -> tuple[Group | None, bool]:
    """Returns (group, joined). `joined` is False when the caller was already a member.

    Idempotent (decision G-I), matching decision 4-D under which POST /v1/library returns 200
    rather than 409 for a title already tracked: re-pasting a code you already used is not a
    failure.
    """
    group = await resolve_invite_code(session, code, now=now)
    if group is None:
        return None, False

    existing = await session.scalar(
        select(GroupMember).where(GroupMember.group_id == group.id, GroupMember.user_id == user.id)
    )
    if existing is not None:
        return group, False

    try:
        # A SAVEPOINT, not a bare insert: Postgres aborts the whole transaction on a constraint
        # violation, so SOMETHING has to unwind before the next statement. `session.rollback()`
        # would unwind the CALLER's transaction — this service leaves commit and rollback to the
        # route everywhere else, and Task 6 creates a user and joins a group in one request, so a
        # lost race there would silently discard the new user and still answer 200. begin_nested
        # scopes the unwind to the failed insert, leaving the enclosing transaction and the
        # already-loaded `group` intact.
        async with session.begin_nested():
            await add_member(session, group_id=group.id, user_id=user.id, role=GroupRole.MEMBER)
    except IntegrityError:
        # TWO constraints can fail here and they mean opposite things, so ask the database which
        # one it was rather than assuming.
        #
        # A lost race on the (group_id, user_id) unique constraint means "already a member" —
        # the constraint is the arbiter, and the loser gets the same answer it would have got a
        # millisecond earlier.
        #
        # A foreign-key violation means the group was deleted underneath us: remove_member takes
        # FOR UPDATE on the `groups` row and drops the group when its last member leaves, while
        # this INSERT needs FOR KEY SHARE on that same row. A join that resolved the code just
        # before a departure commits therefore blocks for the whole leave transaction and then
        # fails the FK deterministically. Reporting `(group, False)` there would answer 200 with
        # GroupWithInvite — handing the caller a group AND its invite code that no longer exist
        # (expire_on_commit=False lets the stale object serialise without a murmur).
        already = await session.scalar(
            select(GroupMember.id).where(GroupMember.group_id == group.id, GroupMember.user_id == user.id)
        )
        return (group, False) if already is not None else (None, False)
    return group, True


async def list_groups(session: AsyncSession, *, user_id: uuid.UUID) -> list[Group]:
    statement = (
        select(Group)
        .join(GroupMember, GroupMember.group_id == Group.id)
        .where(GroupMember.user_id == user_id)
        .order_by(Group.created_at.asc(), Group.id.asc())
    )
    return list(await session.scalars(statement))


async def list_members(session: AsyncSession, *, group_id: uuid.UUID) -> list[tuple[GroupMember, User]]:
    statement = (
        select(GroupMember, User)
        .join(User, User.id == GroupMember.user_id)
        .where(GroupMember.group_id == group_id)
        .order_by(GroupMember.joined_at.asc(), GroupMember.id.asc())
    )
    return list((await session.execute(statement)).all())


async def rotate_invite_code(session: AsyncSession, *, group: Group, now: datetime) -> Group:
    """Rotation is the revocation mechanism — there is no per-invite tracking. It issues a new
    expiry as well as a new code, so an owner adding a seventh housemate next month just rotates.
    """
    group.invite_code = await _fresh_code(session)
    group.invite_code_expires_at = _expiry(now)
    await session.flush()
    return group


class NotAMember(Exception):
    """The target is not in this group. Route turns it into the same 404 the dependency uses."""


class NotPermitted(Exception):
    """A member tried to remove somebody other than themselves."""


async def remove_member(
    session: AsyncSession, *, group_id: uuid.UUID, actor: GroupMember, target_user_id: uuid.UUID
) -> None:
    """Remove `target_user_id` from the group, applying G-E's two lifecycle rules.

    BOTH rules live here rather than in the route, so the self-removal path and the
    owner-removal path cannot drift apart — they are the same code.

    Deleting the group cascades `group_members` by foreign key. In Phase 7.5b it will also
    cascade `group_watchlist`, so an emptied group takes the shared list with it. That is
    written down now rather than discovered then.
    """
    is_self = target_user_id == actor.user_id
    if not is_self and actor.role != GroupRole.OWNER:
        raise NotPermitted

    # SELECT ... FOR UPDATE on the GROUP row, taken before anything else is read: it makes the
    # whole leave path serial per group. Without it, under READ COMMITTED, an owner and a member
    # leaving at the same moment both succeed and the group survives with zero members and no
    # owner — the exact state G-E exists to prevent, and permanently un-joinable once the invite
    # code expires. (tx2 read the member's row as MEMBER before tx1's promotion committed, so it
    # skipped the transfer branch and simply deleted the row tx1 had just made owner.) The group
    # row is the natural lock: it is the one row every participant in the race touches, so no
    # ordering between two locks exists to deadlock on.
    group = await session.scalar(select(Group).where(Group.id == group_id).with_for_update())

    target = await session.scalar(
        select(GroupMember)
        .where(GroupMember.group_id == group_id, GroupMember.user_id == target_user_id)
        # populate_existing, or the lock above buys nothing on the self-removal path: the actor's
        # own membership row is already in this session's identity map, loaded by
        # require_membership BEFORE the lock was acquired, and the ORM hands back an identity-map
        # hit with its stale `role` rather than the columns this statement just read.
        .execution_options(populate_existing=True)
    )
    if target is None:
        raise NotAMember

    if target.role == GroupRole.OWNER:
        successor = await session.scalar(
            select(GroupMember)
            .where(GroupMember.group_id == group_id, GroupMember.user_id != target_user_id)
            # (joined_at, id): joined_at alone is not unique — two people joining inside one
            # transaction share `now()` — so the id keeps the ordering TOTAL. It does not make
            # the winner the earliest joiner in that case; ids are random uuid4s. In production
            # each join is its own transaction, so joined_at genuinely separates them.
            .order_by(GroupMember.joined_at.asc(), GroupMember.id.asc())
            .limit(1)
        )
        if successor is None:
            # Nobody left to own it. Delete the group; the membership cascades.
            await session.delete(group)
            await session.flush()
            return
        successor.role = GroupRole.OWNER

    await session.delete(target)
    await session.flush()


FEED_SORT_KEY = "created_at"


def parse_created_at(raw: str) -> datetime:
    """Total into timestamptz's domain. Every failure here is client-supplied cursor content, so
    it must raise ValueError for decode_cursor to turn into InvalidCursor rather than a 500.

    A naive datetime is silently reinterpreted in the SERVER's timezone against a timestamptz
    column, so pagination quietly walks the wrong window. Bounded at both ends for the reason
    library/service.py documents: datetime.min encodes as `-infinity`, which sorts below
    everything and makes a descending comparison match every row — the same failure mode as a
    NaN score cursor.
    """
    value = datetime.fromisoformat(raw)
    if value.tzinfo is None:
        raise ValueError("cursor value must be timezone-aware")
    if not (datetime(1, 1, 2, tzinfo=UTC) <= value <= datetime(9999, 1, 1, tzinfo=UTC)):
        raise ValueError("cursor value is outside the column's range")
    return value


async def list_feed(
    session: AsyncSession, *, group_id: uuid.UUID, limit: int, cursor: Cursor | None, now: datetime
) -> tuple[list[FeedItem], str | None]:
    """Read-fanout: "activity by members of this group", resolved at query time.

    No per-group rows exist (design doc §5.3), so joining a group shows history instantly and
    leaving revokes instantly, with no denormalised state to repair.

    LEFT OUTER JOIN on media, and it is load-bearing (S-H): `imported` rows carry media_id = NULL,
    and an inner join would silently drop every import summary — the one row type S-A exists to
    create.
    """
    members = select(GroupMember.user_id).where(GroupMember.group_id == group_id)
    statement = (
        select(Activity, Media, User.username)
        .outerjoin(Media, Media.id == Activity.media_id)
        .join(User, User.id == Activity.user_id)
        .where(Activity.user_id.in_(members))
        .order_by(Activity.created_at.desc(), Activity.id.desc())
        .limit(limit + 1)
    )
    if cursor is not None:
        statement = statement.where(tuple_(Activity.created_at, Activity.id) < (cursor.value, cursor.id))

    rows = (await session.execute(statement)).all()
    has_more = len(rows) > limit
    rows = rows[:limit]

    items = [
        FeedItem(
            id=row.Activity.id,
            actor=FeedActor(id=row.Activity.user_id, username=row.username),
            kind=row.Activity.kind,
            media=to_detail(row.Media, now) if row.Media is not None else None,
            payload=row.Activity.payload,
            created_at=row.Activity.created_at,
        )
        for row in rows
    ]
    next_cursor = (
        encode_cursor(FEED_SORT_KEY, rows[-1].Activity.created_at, rows[-1].Activity.id) if has_more and rows else None
    )
    return items, next_cursor


async def list_group_reviews(session: AsyncSession, *, group_id: uuid.UUID, media_id: uuid.UUID) -> list[ReviewRead]:
    """Reviews of one title by this group's members. Bounded by membership, so the route returns
    a plain list rather than a cursor page (S-J).

    The author is JOINED, not lazy-loaded, and that is load-bearing rather than an optimisation.
    A lazy `Review.user` would be a many-to-one on the target's PRIMARY KEY, which takes
    SQLAlchemy's `load_on_pk_identity` identity-map shortcut: it returns the User with no
    statement emitted whenever that row is already in the session, and raises MissingGreenlet the
    moment it is not. A real request's session is always the second case.

    That asymmetry is also why this is a join rather than a relationship plus a remembered
    `selectinload`: the eagerness stays at the only call site instead of being an attribute a
    future caller can touch from a session where it happens to be unloaded. Same shape as
    `list_feed` above, which solves the identical problem for FeedActor.

    Pinned by test_the_group_read_attributes_each_review_to_its_own_author, which expunges the
    identity map before the request so the shortcut cannot fire. It took a mutation to find that
    the obvious version of that test proved nothing.
    """
    members = select(GroupMember.user_id).where(GroupMember.group_id == group_id)
    statement = (
        select(Review, User)
        .join(User, User.id == Review.user_id)
        .where(Review.media_id == media_id, Review.user_id.in_(members))
        .order_by(Review.created_at.asc(), Review.id.asc())
    )
    rows = (await session.execute(statement)).all()
    return [to_review_read(row.Review, row.User) for row in rows]


# Deliberately NOT "created_at", which is what FEED_SORT_KEY is. decode_cursor's sort guard
# compares this string, so two endpoints sharing one value means a feed cursor decodes cleanly
# against the watchlist and repositions the caller in a window that means nothing. Harmless
# today — both endpoints are gated on the same group, and a cursor is unsigned and opaque by
# design — but the guard reads stronger than it is. Renaming ONE side resolves the collision;
# Task 3's contract stays untouched.
WATCHLIST_SORT_KEY = "watchlist_created_at"


async def _find_entry(session: AsyncSession, *, group_id: uuid.UUID, media_id: uuid.UUID) -> GroupWatchlist | None:
    """The one definition of "this group's entry for this title".

    Both of propose_title's lookups go through it — the dedupe check and the lost-race recovery —
    so the two cannot drift into asking different questions about the same unique constraint.
    """
    return await session.scalar(
        select(GroupWatchlist).where(GroupWatchlist.group_id == group_id, GroupWatchlist.media_id == media_id)
    )


async def list_watchlist(
    session: AsyncSession, *, group_id: uuid.UUID, limit: int, cursor: Cursor | None, now: datetime
) -> tuple[list[WatchlistItem], str | None]:
    """Cursor-paginated: unlike the member list, a watchlist grows without bound (S-J).

    An INNER join on media, unlike list_feed's outer one: group_watchlist.media_id is NOT NULL
    and an FK, so there is no row this can drop.

    A join rather than a `GroupWatchlist.media` relationship, for the reason list_group_reviews
    spells out: a lazy many-to-one on the target's PRIMARY KEY takes SQLAlchemy's identity-map
    shortcut and returns without emitting a statement whenever the row happens to be loaded, then
    raises MissingGreenlet the moment it is not — which is every real request. Keeping the
    eagerness in the statement means there is no attribute for a later caller to touch.
    """
    statement = (
        select(GroupWatchlist, Media)
        .join(Media, Media.id == GroupWatchlist.media_id)
        .where(GroupWatchlist.group_id == group_id)
        .order_by(GroupWatchlist.created_at.desc(), GroupWatchlist.id.desc())
        .limit(limit + 1)
    )
    if cursor is not None:
        statement = statement.where(tuple_(GroupWatchlist.created_at, GroupWatchlist.id) < (cursor.value, cursor.id))

    rows = (await session.execute(statement)).all()
    has_more = len(rows) > limit
    rows = rows[:limit]
    items = [
        WatchlistItem(
            id=row.GroupWatchlist.id,
            media=to_detail(row.Media, now),
            proposed_by=row.GroupWatchlist.proposed_by,
            created_at=row.GroupWatchlist.created_at,
        )
        for row in rows
    ]
    next_cursor = (
        encode_cursor(WATCHLIST_SORT_KEY, rows[-1].GroupWatchlist.created_at, rows[-1].GroupWatchlist.id)
        if has_more and rows
        else None
    )
    return items, next_cursor


async def propose_title(
    session: AsyncSession, *, group_id: uuid.UUID, media_id: uuid.UUID, user_id: uuid.UUID
) -> GroupWatchlist:
    """Idempotent (S-I): two housemates proposing the same show is agreement, not a conflict.

    Raises MediaMissing — the library service's, not a second name for one condition — when
    media_id matches no row. The route turns it into the same 404 POST /v1/reviews gives.
    """
    existing = await _find_entry(session, group_id=group_id, media_id=media_id)
    if existing is not None:
        return existing

    entry = GroupWatchlist(group_id=group_id, media_id=media_id, proposed_by=user_id)
    try:
        # A SAVEPOINT, so a lost race does not unwind the caller's transaction — the discipline
        # 7.5a established after session.rollback() was found to discard a caller's pending work.
        #
        # `session.add` belongs INSIDE the nested block, as join_by_code/add_member and
        # create_review all do it. Adding first and wrapping only the flush does NOT work: the
        # pending entry is then part of the snapshot the nested transaction was opened on, so
        # rolling that savepoint back neither expunges it nor confines the exception, and the
        # caller's next statement raises PendingRollbackError instead of proceeding. That shape
        # reads correctly and buys nothing, which is why it has now shipped green twice.
        async with session.begin_nested():
            session.add(entry)
            await session.flush()
    except IntegrityError as exc:
        # `except IntegrityError` alone is broader than the constraint it documents.
        # group_watchlist.media_id is an FK and — unlike everywhere before Task 4 — the media_id
        # is CLIENT-SUPPLIED, so an id matching no row fails the same flush a duplicate does.
        # Reading that as the unique constraint sends the recovery lookup after a row that was
        # never written, and the route then evaluates `entry.id` on None: a 500 on ordinary bad
        # input. Ask the database which constraint it was.
        sqlstate = getattr(exc.orig, "sqlstate", None)
        if sqlstate == FOREIGN_KEY_VIOLATION:
            # group_id and proposed_by are ALSO foreign keys here, so a group deleted by its last
            # member leaving (G-E) between the dependency's membership check and this flush lands
            # in this branch too and answers "no such title". The status is right and the wording
            # is not; narrow this to the constraint name if that race ever matters.
            raise MediaMissing from exc
        if sqlstate != UNIQUE_VIOLATION:
            # An integrity error we did not anticipate is not evidence for whichever answer is
            # listed last. Re-raised rather than guessed at.
            raise
        # Lost the race on uq(group_id, media_id); the winner's row is committed and visible to
        # this new statement.
        winner = await _find_entry(session, group_id=group_id, media_id=media_id)
        if winner is None:
            # Unreachable in theory — a 23505 means a conflicting row committed, and READ
            # COMMITTED takes a fresh snapshot per statement, so the lookup above must see it.
            # Re-raised rather than returned, because returning None here is exactly the 500 the
            # discrimination above exists to remove.
            raise
        return winner
    return entry


async def remove_watchlist_entry(session: AsyncSession, *, group_id: uuid.UUID, entry_id: uuid.UUID) -> bool:
    """Any member may remove any entry (S-L). Returns whether anything was removed.

    Scoped to group_id, not entry_id alone: without it a member of one group could delete another
    group's entries by id, since GroupMemberDep only proves membership of the group in the PATH.
    """
    entry = await session.scalar(
        select(GroupWatchlist).where(GroupWatchlist.id == entry_id, GroupWatchlist.group_id == group_id)
    )
    if entry is None:
        return False
    await session.delete(entry)
    await session.flush()
    return True


async def compare_progress(session: AsyncSession, *, group_id: uuid.UUID, media_id: uuid.UUID) -> list[ProgressEntry]:
    """Who is ahead. No new table — this reads user_media.

    Raw episode numbers, no clamping (design doc §5.3). Ordered by progress descending because
    "who's ahead" is the question the endpoint answers; the username tiebreak keeps the list
    stable when several members sit on the same episode, which is the ordinary case in a group
    watching together.

    Scoped by `UserMedia.user_id.in_(members)`, and that clause is the authorization boundary
    rather than a filter: without it the endpoint reports every user on the instance who tracks
    the title to anybody in any group. Pinned by
    test_another_groups_tracker_is_never_in_this_groups_comparison.

    `User` is JOINED explicitly, the same shape as `list_feed` and `list_group_reviews`. There is
    no relationship on UserMedia to lazy-load today, and this is why one is not added: a
    many-to-one on the target's PRIMARY KEY takes SQLAlchemy's `load_on_pk_identity` identity-map
    shortcut, so it emits no statement when the row happens to be loaded and raises
    MissingGreenlet from the cold session a real request always has.
    """
    members = select(GroupMember.user_id).where(GroupMember.group_id == group_id)
    statement = (
        select(UserMedia, User.id.label("member_id"), User.username)
        .join(User, User.id == UserMedia.user_id)
        .where(UserMedia.media_id == media_id, UserMedia.user_id.in_(members))
        .order_by(UserMedia.progress.desc(), User.username.asc())
    )
    rows = (await session.execute(statement)).all()
    return [
        ProgressEntry(
            member=FeedActor(id=row.member_id, username=row.username),
            status=row.UserMedia.status,
            progress=row.UserMedia.progress,
        )
        for row in rows
    ]
