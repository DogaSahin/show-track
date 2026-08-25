import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import ColumnElement, func, select, tuple_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import InstrumentedAttribute

from app.db import BULK_INSERT_CHUNK_SIZE, FOREIGN_KEY_VIOLATION, UNIQUE_VIOLATION, chunked
from app.library import activity
from app.library.models import Activity, ActivityKind, Review, UserMedia, UserMediaStatus
from app.library.schemas import LibraryEntry, LibrarySort, ReviewAuthor, ReviewRead
from app.media import service as media_service
from app.media.models import Media
from app.pagination import Cursor, encode_cursor
from app.users.models import User


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


async def _emit(
    session: AsyncSession,
    *,
    user_id: uuid.UUID,
    media_id: uuid.UUID | None,
    kind: ActivityKind,
    payload: dict[str, Any] | None = None,
) -> None:
    """Write one activity row IN THE CALLER'S TRANSACTION (decision S-E).

    Flushes, never commits — the route owns the transaction boundary, which is what makes an
    activity row and the user_media change it describes inseparable by construction rather than
    by discipline. 7.5.3's acceptance criterion — a failed library mutation writes none — then
    has something real to test.
    """
    session.add(Activity(user_id=user_id, media_id=media_id, kind=kind, payload=payload or {}))
    await session.flush()


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
    entry = await session.get(UserMedia, entry_id)
    # Only on creation: the early return above already covered the already-tracked case, so
    # re-adding a title stays a no-op the feed never hears about.
    await _emit(session, user_id=user_id, media_id=media_id, kind=ActivityKind.ADDED)
    return entry, True


@dataclass(frozen=True, slots=True)
class SortSpec:
    column: InstrumentedAttribute
    descending: bool
    # None for a NOT NULL column, where no COALESCE is emitted at all.
    sentinel: object | None
    parse: Callable[[str], object]


# NOT datetime.max. asyncpg special-cases datetime.max and encodes it as Postgres `infinity`,
# which round-trips back into Python as a NAIVE datetime (tzinfo=None) — measured:
#
#   datetime.max -> stored 'infinity'                   roundtrip tzinfo=None
#   9999-01-01   -> stored '9999-01-01 00:00:00+00'     roundtrip tzinfo=UTC
#
# With datetime.max, every NULL-date row's sort_value comes back naive, encode_cursor emits an
# offset-less string, and _parse_next_episode_date then rejects the server's OWN cursor: a 400
# the moment a page boundary lands in the NULL tail, which for a library of finished shows is
# the ordinary case. Any value Postgres stores as a real timestamp works; this one is legible.
NEXT_EPISODE_SENTINEL = datetime(9999, 1, 1, tzinfo=UTC)
# Below the score_range CHECK floor of 1.0, so it can never collide with a real score.
SCORE_SENTINEL = Decimal("-1")


def _parse_score(raw: str) -> Decimal:
    """Total into NUMERIC(3,1)'s domain — the bind inherits that type from the COALESCE, so
    anything outside it is an asyncpg NumericValueOutOfRangeError, i.e. an unhandled 500 from a
    client-supplied cursor. Measured: 99.9 binds, 100 and -100 do not.

    The finiteness check is separate and equally load-bearing: Decimal() accepts "NaN",
    "Infinity" and "sNaN", and Postgres orders NaN ABOVE every numeric value, so a NaN cursor
    makes the descending row comparison true for every row — no error, no 400, the client simply
    receives page one again forever with a fresh valid cursor each time.
    """
    value = Decimal(raw)
    if not value.is_finite():
        raise ValueError("score cursor value must be finite")
    if not (SCORE_SENTINEL <= value <= Decimal("10")):
        # The sentinel is the floor, not 1.0: a cursor issued for an unrated row carries it.
        raise ValueError("score cursor value is outside the column's range")
    return value


def _parse_next_episode_date(raw: str) -> datetime:
    """A naive datetime is silently reinterpreted in the SERVER's local timezone against a
    timestamptz column, so pagination quietly walks the wrong window. Bounded at both ends for
    the same reason as _parse_score: datetime.min encodes as `-infinity`, which sorts below
    everything and makes an ASCENDING comparison match every row — the NaN failure mode again.
    """
    value = datetime.fromisoformat(raw)
    if value.tzinfo is None:
        raise ValueError("date cursor value must be timezone-aware")
    if not (datetime(1, 1, 2, tzinfo=UTC) <= value <= NEXT_EPISODE_SENTINEL):
        raise ValueError("date cursor value is outside the column's range")
    return value


def _parse_title(raw: str) -> str:
    """Postgres rejects NUL in `text`, which would surface as a 500 from client input.

    No length cap: Media.title is unbounded Text, so any cap this side could reject a cursor
    encode_cursor legitimately emitted. Overall size is bounded by Query(max_length=2048) on the
    cursor parameter instead, which is the right place for it.
    """
    if "\x00" in raw:
        raise ValueError("title cursor value contains a NUL byte")
    return raw


# NULLs land last under every direction, via COALESCE rather than a NULLS LAST clause plus a
# two-branch WHERE. NULL poisons row comparison — `(score, id) < (:v, :id)` evaluates to NULL,
# not true — so the naive keyset silently drops every unrated title.
#
# Each sentinel lies outside its column's legal range, so it can never collide with a real
# value. This is a magic value for "absent", which the AniList score-0 rule bans — the difference
# is that this one is a query-time projection and is never stored.
#
# Direction is a property of the field (decision 4-J): best-rated, soonest-airing, A-Z.
SORTS: dict[LibrarySort, SortSpec] = {
    LibrarySort.SCORE: SortSpec(UserMedia.score, True, SCORE_SENTINEL, _parse_score),
    LibrarySort.NEXT_EPISODE_DATE: SortSpec(
        Media.next_episode_date, False, NEXT_EPISODE_SENTINEL, _parse_next_episode_date
    ),
    LibrarySort.TITLE: SortSpec(Media.title, False, None, _parse_title),
}


def sort_expression(spec: SortSpec) -> ColumnElement[Any]:
    return func.coalesce(spec.column, spec.sentinel) if spec.sentinel is not None else spec.column


async def get_entry(
    session: AsyncSession, *, entry_id: uuid.UUID, user_id: uuid.UUID
) -> tuple[UserMedia, Media] | None:
    """Ownership is in the WHERE clause, not a post-fetch check.

    That is what makes "this entry is not yours" and "this entry does not exist" the same answer
    with no branch to forget — and the reason the route can only ever produce a 404.
    """
    row = (
        await session.execute(
            select(UserMedia, Media)
            .join(Media, UserMedia.media_id == Media.id)
            .where(UserMedia.id == entry_id, UserMedia.user_id == user_id)
        )
    ).first()
    return (row.UserMedia, row.Media) if row is not None else None


async def update_entry(session: AsyncSession, entry: UserMedia, changes: dict[str, Any]) -> UserMedia:
    """`changes` comes from model_dump(exclude_unset=True), so an absent field never appears here
    and an explicit null does. An empty dict dirties nothing, so SQLAlchemy emits no UPDATE and
    `updated_at` is not bumped — correct, because nothing was updated.

    The `refresh` is load-bearing, not defensive. `updated_at` carries `onupdate=func.now()`, a
    SQL-expression default: SQLAlchemy cannot know the value the server computed, so it EXPIRES
    the attribute after the UPDATE flush. `expire_on_commit=False` does not help — that governs
    commit, not flush-time expiry. The route then reads `entry.updated_at` while serialising,
    which is a synchronous attribute access triggering a lazy reload inside async code:
    MissingGreenlet, a 500 raised AFTER the route has already committed the write.

    Measured against the dev database:
        EXPIRED after UPDATE flush: {'updated_at'}
        access -> MissingGreenlet: greenlet_spawn has not been called
    """
    if not changes:
        return entry

    for field, value in changes.items():
        setattr(entry, field, value)
    await session.flush()
    await session.refresh(entry)

    kind = activity.kind_for(changes)
    if kind is not None:
        await _emit(
            session,
            user_id=entry.user_id,
            media_id=entry.media_id,
            kind=kind,
            payload=activity.payload_for(changes),
        )
    return entry


async def delete_entry(session: AsyncSession, entry: UserMedia) -> None:
    """Deletes only the library entry. The shared `media` row is untouched — nothing here can
    reach it, since the CASCADE is declared on user_media.media_id and so fires only when a
    MEDIA row is deleted.
    """
    await session.delete(entry)
    await session.flush()


async def list_entries(
    session: AsyncSession,
    *,
    user_id: uuid.UUID,
    sort: LibrarySort,
    limit: int,
    status: UserMediaStatus | None,
    cursor: Cursor | None,
    now: datetime,
) -> tuple[list[LibraryEntry], str | None]:
    """Keyset pagination over a composite (sort_value, id).

    The `id` tiebreaker takes the SAME direction as the primary sort. Mixed directions cannot be
    written as a row comparison at all, and `tuple_(a, b) < (x, y)` — a genuine Postgres row
    comparison, evaluated lexicographically — is what lets one predicate do the work of
    `(a < x) OR (a = x AND b < y)`.

    `limit + 1` is the has-more probe. A COUNT(*) here would be a second scan to answer a
    question the extra row already answered.

    Sorting on a `media` column while filtering on `user_media.user_id` cannot use an index for
    the ordering — Postgres fetches the user's rows and sorts them. That is bounded by one
    person's library, and it is why the COALESCE costs nothing that was not already being paid.
    """
    spec = SORTS[sort]
    expression = sort_expression(spec)

    # The sort value is selected as a column so the cursor is built from the value POSTGRES
    # computed, not from one recomputed in Python. Recomputing is where a coalesce and a
    # comparison drift apart.
    statement = (
        select(UserMedia, Media, expression.label("sort_value"))
        .join(Media, UserMedia.media_id == Media.id)
        .where(UserMedia.user_id == user_id)
        .limit(limit + 1)
    )
    if status is not None:
        statement = statement.where(UserMedia.status == status)
    if cursor is not None:
        key = tuple_(expression, UserMedia.id)
        position = (cursor.value, cursor.id)
        statement = statement.where(key < position if spec.descending else key > position)
    statement = statement.order_by(
        expression.desc() if spec.descending else expression.asc(),
        UserMedia.id.desc() if spec.descending else UserMedia.id.asc(),
    )

    rows = (await session.execute(statement)).all()
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = encode_cursor(sort.value, rows[-1].sort_value, rows[-1].UserMedia.id) if has_more and rows else None
    return [to_entry(row.UserMedia, row.Media, now) for row in rows], next_cursor


async def bulk_add_entries(session: AsyncSession, *, user_id: uuid.UUID, rows: Sequence[dict[str, Any]]) -> int:
    """Insert the rows that are missing, leave the rest alone, and return how many landed.

    ON CONFLICT DO NOTHING is what makes "local wins" a DATABASE property rather than application
    logic: there is no code path that could overwrite an existing score or progress, so no future
    refactor can introduce one by accident. The same philosophy as the notification dedup
    constraint.

    DO NOTHING here, rather than the no-op DO UPDATE used for `media`, because the intent is the
    opposite: there we needed the conflicting row's id back, here we need the conflicting row
    left untouched. DO NOTHING also tolerates duplicate conflict keys inside one statement, which
    DO UPDATE does not.
    """
    # user_id is stamped HERE, not carried inside caller-supplied dicts. Every other function in
    # this module takes it as a keyword-only parameter, which is what makes "scoped to the
    # caller" structural instead of a convention another module has to remember.
    #
    # Sorted by media_id for the same reason persist_media_bulk sorts: a stable global lock
    # order. Two concurrent imports by the same user would otherwise take user_media index locks
    # in arrival order and can deadlock.
    stamped = sorted(({**row, "user_id": user_id} for row in rows), key=lambda row: row["media_id"])

    inserted = 0
    for chunk in chunked(stamped, BULK_INSERT_CHUNK_SIZE):
        statement = (
            pg_insert(UserMedia)
            .values(list(chunk))
            .on_conflict_do_nothing(index_elements=["user_id", "media_id"])
            .returning(UserMedia.id)
        )
        inserted += len((await session.execute(statement)).all())

    if inserted:
        # media_id is None: an import is about N titles, not one (S-A/S-D). Guarded on the count
        # because re-importing is idempotent by design, and "imported 0 titles" is not an event.
        await _emit(
            session,
            user_id=user_id,
            media_id=None,
            kind=ActivityKind.IMPORTED,
            payload={"count": inserted},
        )
    return inserted


def to_review_read(review: Review, author: User) -> ReviewRead:
    """The one place a Review becomes wire format, so the nested author cannot be forgotten on
    one of the four routes that return one."""
    return ReviewRead(
        id=review.id,
        author=ReviewAuthor(id=author.id, username=author.username),
        media_id=review.media_id,
        body=review.body,
        contains_spoilers=review.contains_spoilers,
        created_at=review.created_at,
        updated_at=review.updated_at,
    )


class ReviewExists(Exception):
    """One review per person per title. The route turns this into a 409 (decision S-K)."""


class MediaMissing(Exception):
    """No `media` row for the supplied media_id. The route turns this into a 404."""


async def create_review(
    session: AsyncSession, *, user_id: uuid.UUID, media_id: uuid.UUID, body: str, contains_spoilers: bool
) -> Review:
    review = Review(user_id=user_id, media_id=media_id, body=body, contains_spoilers=contains_spoilers)
    try:
        # Scoped to a SAVEPOINT so a duplicate does not unwind the caller's transaction — the
        # discipline 7.5a established after session.rollback() was found to discard a caller's
        # pending work.
        #
        # The `session.add` belongs INSIDE the savepoint, exactly as join_by_code/add_member do
        # it. Adding first and only wrapping the flush does NOT work: the pending Review is then
        # part of the snapshot the nested transaction was opened on, so rolling that savepoint
        # back does not expunge it, and the session is left with a _rollback_exception — the
        # caller's very next statement raises PendingRollbackError instead of proceeding.
        # Measured: with the add outside, the follow-up query in
        # test_a_duplicate_does_not_unwind_the_callers_pending_work raised
        # "This Session's transaction has been rolled back due to a previous exception during
        # flush", which is the precise failure the savepoint exists to prevent.
        async with session.begin_nested():
            session.add(review)
            await session.flush()
    except IntegrityError as exc:
        # `except IntegrityError` alone is broader than the constraint it means. reviews.media_id
        # is an FK, so a media_id that matches no row raises here too — and reporting that as
        # "you have already reviewed this title" sends the client to a review it cannot PATCH and
        # a group list that comes back empty. This is the first endpoint in the codebase to take
        # a client-supplied internal media_id (POST /v1/library resolves source/external_id
        # through get_or_create_media), so no earlier route could reach an FK violation this way.
        sqlstate = getattr(exc.orig, "sqlstate", None)
        if sqlstate == UNIQUE_VIOLATION:
            raise ReviewExists from exc
        if sqlstate == FOREIGN_KEY_VIOLATION:
            # Reads the 23503 as a bad media_id, and reviews.user_id is ALSO an FK. Unreachable
            # today because user_id comes from current_user, which is always a live row — but a
            # future route taking a client-supplied user_id would inherit exactly the
            # misclassification this branch exists to fix. Narrow it to the constraint name then.
            raise MediaMissing from exc
        # Deliberately re-raised rather than folded into either branch: an integrity error we did
        # not anticipate is not evidence for whichever answer happens to be listed last, and
        # guessing here would recreate exactly the misclassification above.
        raise
    return review


async def get_own_review(session: AsyncSession, *, review_id: uuid.UUID, user_id: uuid.UUID) -> Review | None:
    """Scoped to the caller. A review you do not own is indistinguishable from one that does not
    exist, so the endpoint never confirms which ids are real."""
    return await session.scalar(select(Review).where(Review.id == review_id, Review.user_id == user_id))


async def update_review(session: AsyncSession, review: Review, changes: dict[str, Any]) -> Review:
    if not changes:
        return review
    for field, value in changes.items():
        setattr(review, field, value)
    await session.flush()
    # updated_at carries onupdate=func.now(), a SQL-expression default that SQLAlchemy EXPIRES
    # after the flush. Serialising it without this refresh is a MissingGreenlet 500 — the exact
    # trap update_entry documents.
    await session.refresh(review)
    return review


async def delete_review(session: AsyncSession, review: Review) -> None:
    await session.delete(review)
    await session.flush()
