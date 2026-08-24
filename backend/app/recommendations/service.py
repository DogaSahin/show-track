import logging
import uuid
from collections import defaultdict
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func, insert, select, text, tuple_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import aliased

from app.config import get_settings
from app.db import BULK_INSERT_CHUNK_SIZE, chunked, get_sessionmaker
from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource
from app.media.providers.base import MediaProvider, MediaRef
from app.media.providers.errors import ProviderError
from app.media.service import persist_media_bulk, to_persisted
from app.pagination import Cursor, encode_cursor
from app.recommendations import scoring
from app.recommendations.models import MediaSimilarity, Recommendation, RecommendationRun
from app.recommendations.schemas import RecommendationItem, RecommendationReason, SeedSummary
from app.recommendations.scoring import SCORE_THRESHOLD
from app.sync.locks import RECOMPUTE_LOCK_KEY, SEED_LOCK_KEY, advisory_lock

logger = logging.getLogger(__name__)


# A library entry is a positive signal when it is scored at or above the threshold, or favourited,
# or completed. Expressed once, here, because BOTH the seed worklist and the taste profile need
# exactly the same predicate — and them drifting apart would mean seeding from titles that then
# contribute nothing to the ranking.
def positive_signal_clause():
    return (
        (UserMedia.score >= SCORE_THRESHOLD)
        | (UserMedia.favorite.is_(True))
        | (UserMedia.status == UserMediaStatus.COMPLETED)
    )


async def collect_seeds(session: AsyncSession, *, now: datetime, force: bool = False) -> Sequence[Media]:
    """Distinct media that are a positive signal for ANY user and are due a refresh.

    GLOBAL, not per user (decision 7-A): titles are shared rows, so three people rating the same
    title highly is one provider call rather than three. Single-user today; structurally correct
    when groups land.
    """
    cadence = timedelta(hours=get_settings().recommendations_seed_hours)
    freshest = (
        select(MediaSimilarity.source_media_id, func.max(MediaSimilarity.fetched_at).label("fetched_at"))
        .group_by(MediaSimilarity.source_media_id)
        .subquery()
    )
    statement = (
        select(Media)
        .join(UserMedia, UserMedia.media_id == Media.id)
        .where(positive_signal_clause())
        .outerjoin(freshest, freshest.c.source_media_id == Media.id)
        .distinct()
    )
    if not force:
        # NULL means "never seeded", which is always due — the same reading Media.last_synced_at
        # gives NULL in the sync job.
        statement = statement.where((freshest.c.fetched_at.is_(None)) | (freshest.c.fetched_at < now - cadence))
    return (await session.scalars(statement)).all()


async def seed_once(
    session: AsyncSession,
    providers: Mapping[MediaSource, MediaProvider],
    *,
    now: datetime,
    force: bool = False,
) -> SeedSummary:
    """One sweep. Takes a session; takes no lock. run_seed owns both of those.

    Split this way so the job is testable inside the test transaction, the same separation
    app/sync/service.py draws between run_sync and apply_refresh.
    """
    seeds = await collect_seeds(session, now=now, force=force)
    if not seeds:
        return SeedSummary(ran=True)

    # Only (id, source, external_id) survives into the loop below, never the ORM objects. Ending
    # the transaction detaches or expires them depending on how they got into the session, and an
    # expired attribute read is a lazy load — MissingGreenlet in async code. collect_worklist in
    # app/sync/service.py narrows to the same three columns for the same reason.
    worklist = [(seed.id, seed.source, seed.external_id) for seed in seeds]

    # Provider calls happen with NO transaction open (decision 4-M). The read above autobegan one
    # and nothing has been written, so ending it here is free and is what keeps a transaction from
    # spanning the HTTP below.
    #
    # COMMIT, not rollback, and do NOT "fix" this back to rollback to match run_sync — the two
    # are not in the same position. run_sync always owns a fresh session, so its rollback can only
    # ever discard its own read; seed_once also runs against a session the CALLER already wrote
    # through, where rollback is ROLLBACK TO SAVEPOINT and takes those writes with it. Measured
    # with the brief's original rollback: the seeded media row vanished and the edge insert failed
    # on fk_media_similarity_source_media_id_media. That is the trap apply_refresh's docstring
    # records. Committing an already-read-only transaction ends it just as completely, leaves the
    # connection idle rather than idle-in-transaction, and cannot destroy work this function did
    # not do.
    await session.commit()

    refs_by_seed: dict[uuid.UUID, tuple[MediaRef, ...]] = {}
    failed = 0
    for seed_id, source, external_id in worklist:
        provider = providers.get(source)
        if provider is None:
            logger.warning("no provider registered for %s; skipping seed %s", source, seed_id)
            failed += 1
            continue
        try:
            refs_by_seed[seed_id] = tuple(await provider.fetch_similar(external_id))
        except ProviderError:
            # Per seed, never per sweep. One provider having a bad minute must not discard the
            # edges every other seed just earned.
            logger.warning("fetch_similar failed for media %s", seed_id, exc_info=True)
            failed += 1

    media_ids, new_media = await _resolve_candidates(session, providers, refs_by_seed)
    edges, changed_seeds = await _write_edges(session, refs_by_seed, media_ids, now=now)
    # Only the seeds whose edges MOVED, never every seed the provider answered for. Invalidating
    # on every answer deletes every active user's cache header on every sweep, which makes
    # recommendations_ttl_hours unreachable — a setting that can never fire.
    await _invalidate(session, sorted(changed_seeds))
    await session.commit()

    return SeedSummary(
        ran=True,
        seeds=len(seeds),
        fetched=len(refs_by_seed),
        new_media=new_media,
        edges=edges,
        failed=failed,
    )


async def _resolve_candidates(
    session: AsyncSession,
    providers: Mapping[MediaSource, MediaProvider],
    refs_by_seed: Mapping[uuid.UUID, tuple[MediaRef, ...]],
) -> tuple[dict[MediaRef, uuid.UUID], int]:
    """Map every candidate ref to a media id, fetching detail only for the genuinely unknown.

    This dedupe is what stops the fan-out being an N+1 against TMDB, whose REST API has no batch
    endpoint: on a mature install most candidates already have a `media` row, so the number of
    detail requests collapses to near zero after the first sweep.
    """
    wanted = {ref for refs in refs_by_seed.values() for ref in refs}
    if not wanted:
        return {}, 0

    known: dict[MediaRef, uuid.UUID] = {}
    for chunk in chunked(sorted(wanted, key=lambda r: (r.source, r.external_id)), BULK_INSERT_CHUNK_SIZE):
        rows = await session.execute(
            select(Media.id, Media.source, Media.external_id).where(
                # A tuple IN is one statement instead of one per source; both sources in one pass.
                func.row(Media.source, Media.external_id).in_([func.row(r.source, r.external_id) for r in chunk])
            )
        )
        for media_id, source, external_id in rows:
            known[MediaRef(source=source, external_id=external_id)] = media_id

    missing = [ref for ref in wanted if ref not in known]
    if not missing:
        return known, 0

    # Decision 4-M again, and the second half of it is the easy one to miss. The lookup above
    # autobegan a transaction, and get_many below is provider HTTP — for TMDB, whose REST API has
    # no batch endpoint, that is the ABC's loop issuing up to SIMILAR_LIMIT sequential requests at
    # 8s apiece. Ending the transaction here is the same move seed_once makes after collect_seeds,
    # for the same reason: app/sync/locks.py records that a managed Postgres with
    # idle_in_transaction_session_timeout kills a connection left in that state, which here would
    # be mid-sweep, after the provider budget has already been spent.
    #
    # COMMIT rather than rollback for the reason seed_once documents: under a caller that shares
    # its session, rollback is ROLLBACK TO SAVEPOINT and would discard that caller's rows.
    # `known` is plain (id, source, external_id) values, never ORM objects, so nothing here is
    # expired by ending the transaction.
    await session.commit()

    by_source: dict[MediaSource, list[str]] = defaultdict(list)
    for ref in missing:
        by_source[ref.source].append(ref.external_id)

    details = []
    for source, external_ids in by_source.items():
        provider = providers.get(source)
        if provider is None:
            continue
        try:
            details.extend((await provider.get_many(external_ids)).values())
        except ProviderError:
            # A candidate we cannot resolve is simply not a candidate this sweep. The edge is
            # skipped and the next sweep retries it.
            logger.warning("could not resolve %d %s candidates", len(external_ids), source, exc_info=True)

    persisted = await persist_media_bulk(session, details)
    known.update(persisted)
    return known, len(persisted)


async def _write_edges(
    session: AsyncSession,
    refs_by_seed: Mapping[uuid.UUID, tuple[MediaRef, ...]],
    media_ids: Mapping[MediaRef, uuid.UUID],
    *,
    now: datetime,
) -> tuple[int, set[uuid.UUID]]:
    """Upsert the edges. Returns (rows written, seeds whose edges actually CHANGED).

    The second element is what `_invalidate` acts on. Reporting it from here rather than
    invalidating every answered seed is what keeps `recommendations_ttl_hours` reachable: an
    unchanged answer must leave the user's cache header alone, or the TTL can never expire
    anything for anyone whose library holds a due seed.
    """
    rows = []
    # Deduped because ON CONFLICT DO UPDATE raises cardinality_violation ("cannot affect row a
    # second time") when ONE statement carries the same conflict key twice — DO NOTHING tolerates
    # it, DO UPDATE does not. Measured: a provider repeating a candidate inside one similar-to
    # list aborted the whole sweep. Note what that isolation is and is not: every seed's edges
    # share ONE transaction, so a DB-level failure here still discards the sweep's writes. What is
    # isolated per seed is a PROVIDER failure, which is the common case and the one the loop above
    # catches. persist_media_bulk guards the identical hazard for the same reason. First wins:
    # refs arrive most-similar-first, so the later duplicate is the strictly worse position.
    seen: set[tuple[uuid.UUID, uuid.UUID]] = set()
    for seed_id, refs in refs_by_seed.items():
        for position, ref in enumerate(refs):
            similar_id = media_ids.get(ref)
            # The CHECK constraint would reject a self-edge anyway; filtering here means one bad
            # row does not abort a whole chunk's INSERT.
            if similar_id is None or similar_id == seed_id:
                continue
            if (seed_id, similar_id) in seen:
                continue
            seen.add((seed_id, similar_id))
            rows.append(
                {
                    "source_media_id": seed_id,
                    "similar_media_id": similar_id,
                    "position": position,
                    "fetched_at": now,
                }
            )
    if not rows:
        return 0, set()

    # Read the current state BEFORE writing over it. One indexed SELECT over the seeds in hand,
    # and the comparison is plain Python.
    #
    # Deliberately NOT expressed as a conditional upsert (`DO UPDATE ... WHERE position IS
    # DISTINCT FROM excluded.position`), which looks tidier and hides a worse bug: a seed whose
    # edges are STABLE would then never refresh fetched_at, so collect_seeds would find it due on
    # every sweep forever. fetched_at must keep advancing unconditionally; only the invalidation
    # is conditional.
    seed_ids = sorted({row["source_media_id"] for row in rows})
    stored: dict[tuple[uuid.UUID, uuid.UUID], int] = {}
    for chunk in chunked(seed_ids, BULK_INSERT_CHUNK_SIZE):
        current = await session.execute(
            select(MediaSimilarity.source_media_id, MediaSimilarity.similar_media_id, MediaSimilarity.position).where(
                MediaSimilarity.source_media_id.in_(chunk)
            )
        )
        for source_id, similar_id, position in current:
            stored[(source_id, similar_id)] = position

    # A stored position of None means the edge is new. Edges the upstream list DROPPED are not a
    # change: nothing in this job deletes edges, so those rows survive and still feed the ranking.
    changed = {
        row["source_media_id"]
        for row in rows
        if stored.get((row["source_media_id"], row["similar_media_id"])) != row["position"]
    }

    written = 0
    for chunk in chunked(rows, BULK_INSERT_CHUNK_SIZE):
        statement = pg_insert(MediaSimilarity).values(list(chunk))
        # DO UPDATE, not DO NOTHING: a re-seed must be able to MOVE a candidate's position and
        # refresh fetched_at, otherwise collect_seeds would keep finding the seed due forever.
        statement = statement.on_conflict_do_update(
            index_elements=["source_media_id", "similar_media_id"],
            set_={"position": statement.excluded.position, "fetched_at": statement.excluded.fetched_at},
        )
        await session.execute(statement)
        written += len(chunk)
    return written, changed


async def _invalidate(session: AsyncSession, seed_ids: Sequence[uuid.UUID]) -> None:
    """Drop the cache header of every user who holds one of these seeds as a positive signal.

    Precise invalidation, so a new candidate appears on the user's next cursor-less read instead
    of waiting out the TTL. The TTL stays as a backstop for the inputs this cannot see.
    """
    if not seed_ids:
        return
    affected = select(UserMedia.user_id).where(UserMedia.media_id.in_(seed_ids)).where(positive_signal_clause())
    await session.execute(delete(RecommendationRun).where(RecommendationRun.user_id.in_(affected)))


async def run_seed(providers: Mapping[MediaSource, MediaProvider], *, now: datetime | None = None) -> SeedSummary:
    """The locked, session-owning entry point the scheduler calls.

    Owns its session rather than borrowing a request's: a job is not a request, and a request's
    transaction must not be held open across the provider calls inside (decision 4-M).
    """
    async with advisory_lock(SEED_LOCK_KEY) as acquired:
        if not acquired:
            return SeedSummary(ran=False)
        async with get_sessionmaker()() as session:
            return await seed_once(session, providers, now=now or datetime.now(tz=UTC))


async def is_stale(session: AsyncSession, *, user_id: uuid.UUID, now: datetime) -> bool:
    """Whether this user's ranking needs rebuilding, cheapest check first.

    There is no dirty-flag column: UserMedia.updated_at already carries onupdate=func.now(), so a
    rating, a favourite or a status change moves it for free. What it cannot see is a DELETION —
    removing an entry moves no surviving row's timestamp — which is what source_entry_count is for.
    New edges need neither: _invalidate drops the run row of every user holding a changed seed. The
    TTL backstops what nothing above can see — a shift in the global genre-IDF denominator, since
    _genre_counts is unfiltered and ANY new media row moves every user's affinity, and a retune of
    the scoring constants.

    Columns, not the entity, and deliberately so: session.get() would return an identity-mapped
    RecommendationRun without querying, and recompute() overwrites that row with a Core pg_insert
    the ORM cannot see — under expire_on_commit=False the commit does not refresh it either. A Row
    holds no identity, so this always reads what is actually in the table.
    """
    row = (
        await session.execute(
            select(RecommendationRun.computed_at, RecommendationRun.source_entry_count).where(
                RecommendationRun.user_id == user_id
            )
        )
    ).one_or_none()
    if row is None:
        return True
    computed_at, source_entry_count = row

    ttl = timedelta(hours=get_settings().recommendations_ttl_hours)
    if computed_at < now - ttl:
        return True

    latest, count = (
        await session.execute(select(func.max(UserMedia.updated_at), func.count()).where(UserMedia.user_id == user_id))
    ).one()
    if count != source_entry_count:
        return True
    return latest is not None and latest > computed_at


async def _taste_profile(session: AsyncSession, user_id: uuid.UUID) -> tuple[list[scoring.TasteEntry], int]:
    """Every library entry, reduced to the scoring dataclass, plus the total entry count.

    ALL entries are loaded, not only the positive ones: signal_weight returns 0.0 for the rest, and
    the count is what is_stale compares against source_entry_count.
    """
    rows = (
        await session.execute(
            select(UserMedia.media_id, Media.genres, UserMedia.score, UserMedia.favorite, UserMedia.status)
            .join(Media, Media.id == UserMedia.media_id)
            .where(UserMedia.user_id == user_id)
        )
    ).all()
    entries = [
        scoring.TasteEntry(
            media_id=media_id,
            genres=tuple(genres or ()),
            score=score,
            favorite=favorite,
            completed=status == UserMediaStatus.COMPLETED,
        )
        for media_id, genres, score, favorite, status in rows
    ]
    return entries, len(rows)


async def _genre_counts(session: AsyncSession) -> dict[str, int]:
    """How many titles carry each canonical genre — the IDF denominator.

    One aggregate over `media`, not one query per genre. There are 27 canonical genres and the
    whole result is a small dict.

    Note the cost scales with the whole `media` table and NOT with the user's library, and no index
    helps a set-returning function in the SELECT list — this is a full scan on every recompute. Fine
    at MVP size; the thing to reach for once `media` is large is a materialised counts table
    refreshed by the seed job.
    """
    genre = func.unnest(Media.genres).label("genre")
    rows = await session.execute(select(genre, func.count()).group_by(genre))
    return {name: count for name, count in rows}


async def _candidates(
    session: AsyncSession, *, user_id: uuid.UUID, seed_ids: Sequence[uuid.UUID]
) -> list[scoring.Candidate]:
    """Fan-out edges from this user's seeds, minus anything already in their library.

    Membership is this join and nothing else. There is deliberately NO genre predicate here: the
    alpha floor in scoring.py exists so a candidate with zero genre overlap can still be carried by
    provider signal, and a `genres && :profile` filter would delete exactly those rows before the
    floor could rescue them (decision 7-J).
    """
    if not seed_ids:
        return []

    owned = select(UserMedia.media_id).where(UserMedia.user_id == user_id)
    rows = (
        await session.execute(
            select(
                MediaSimilarity.similar_media_id,
                MediaSimilarity.source_media_id,
                MediaSimilarity.position,
                Media.genres,
            )
            .join(Media, Media.id == MediaSimilarity.similar_media_id)
            .where(MediaSimilarity.source_media_id.in_(seed_ids))
            .where(MediaSimilarity.similar_media_id.not_in(owned))
        )
    ).all()

    edges: dict[uuid.UUID, list[scoring.Edge]] = defaultdict(list)
    genres: dict[uuid.UUID, tuple[str, ...]] = {}
    for media_id, seed_id, position, media_genres in rows:
        edges[media_id].append(scoring.Edge(seed_media_id=seed_id, position=position))
        genres[media_id] = tuple(media_genres or ())

    return [
        scoring.Candidate(media_id=media_id, genres=genres[media_id], edges=tuple(media_edges))
        for media_id, media_edges in edges.items()
    ]


async def recompute(session: AsyncSession, *, user_id: uuid.UUID, now: datetime) -> int:
    """Rebuild this user's ranking. Returns how many rows were written.

    Pure SQL and pure Python — no provider call, which is what lets this run on a read without
    breaking the DB-only read-path guarantee.
    """
    entries, entry_count = await _taste_profile(session, user_id)
    # Seeds are the POSITIVE entries only, and that filter is load-bearing rather than an
    # optimisation: _candidates attributes every candidate to one of these ids, so widening it
    # would let "because you liked X" name a title the user gave no positive signal for.
    seed_ids = [entry.media_id for entry in entries if scoring.signal_weight(entry) > 0]
    candidates = await _candidates(session, user_id=user_id, seed_ids=seed_ids)
    ranked = scoring.rank_candidates(candidates, entries, await _genre_counts(session))

    # DELETE then INSERT, in one transaction. Simpler than diffing, and correct because the whole
    # ranking is derived — there is no state in these rows worth preserving. It is also the only
    # shape that works: uq_recommendation_user_id_rank is NOT deferrable, so an in-place rewrite
    # (UPDATE ... SET rank = rank + 1) would violate it mid-statement.
    await session.execute(delete(Recommendation).where(Recommendation.user_id == user_id))
    for chunk in chunked(list(enumerate(ranked)), BULK_INSERT_CHUNK_SIZE):
        await session.execute(
            insert(Recommendation),
            [
                {
                    "user_id": user_id,
                    "media_id": scored.media_id,
                    "rank": rank,
                    "score": scored.score,
                    "seed_media_id": scored.seed_media_id,
                    "matched_genres": list(scored.matched_genres),
                }
                for rank, scored in chunk
            ],
        )

    # The header row is written even when `ranked` is empty. That is the whole reason this table
    # exists: without it, "computed, correctly empty" is indistinguishable from "never computed"
    # and the cold-start user recomputes on every request forever.
    statement = pg_insert(RecommendationRun).values(user_id=user_id, computed_at=now, source_entry_count=entry_count)
    await session.execute(
        statement.on_conflict_do_update(
            index_elements=["user_id"],
            set_={"computed_at": now, "source_entry_count": entry_count},
        )
    )
    return len(ranked)


def _user_lock_key(user_id: uuid.UUID) -> int:
    """A stable signed int32 for pg_try_advisory_xact_lock's second argument.

    Derived here rather than with Postgres's hashtext(): that function is internal, undocumented
    and carries no stability guarantee, so a change to it across a major version would silently
    re-map every user to a different lock key. This derivation is ours, deterministic, and
    testable with no database.
    """
    return int.from_bytes(user_id.bytes[:4], "big", signed=True)


async def ensure_fresh(session: AsyncSession, *, user_id: uuid.UUID, now: datetime) -> None:
    """Recompute if stale. Called ONLY on a cursor-less read — that is the whole cursor-stability
    guarantee (decision 7-C): a request carrying a cursor cannot reach this function, so it cannot
    move the ranking it is paginating.

    What that does NOT promise is that the ranking is immutable for the life of a paging session.
    A concurrent cursor-less read from the same user still reaches this function, and the rebuild
    below is DELETE-then-INSERT over the whole ranking. Accepted residual risk, not a gap to close
    here.

    pg_try_advisory_xact_lock, NOT the session-scoped advisory_lock() helper in app/sync/locks.py,
    and this is not an oversight. The xact form is correct here for exactly the reasons that
    module records it is wrong for the jobs: the recompute is pure SQL and is exactly one
    transaction, so it never spans an HTTP call and never survives a mid-function commit. It
    auto-releases on commit or on rollback, with no `finally` to get wrong. Do not "fix" this to
    match locks.py.

    COMMITS the caller's session when it rebuilds — that is what makes the new ranking durable and
    what releases the xact lock. Not reachable on the GET this is built for, which has nothing else
    pending, but it is a real contract: do not call this with uncommitted work you are not ready to
    make permanent.
    """
    if not await is_stale(session, user_id=user_id, now=now):
        return

    acquired = await session.scalar(
        text("SELECT pg_try_advisory_xact_lock(:key, :user_key)"),
        {"key": RECOMPUTE_LOCK_KEY, "user_key": _user_lock_key(user_id)},
    )
    if not acquired:
        # Another request is already rebuilding this user's ranking. Serve what is there rather
        # than duplicating the work; the next request picks up the fresh rows. On a genuinely cold
        # cache that means one of two simultaneous first requests sees an empty list, which
        # self-heals.
        logger.info("recompute already in progress for user %s; serving the existing cache", user_id)
        return

    await recompute(session, user_id=user_id, now=now)
    await session.commit()


SORT_KEY = "rank"


def parse_rank(raw: str) -> int:
    """Total into the rank column's domain. Every failure here is client-supplied cursor content,
    so it must raise ValueError for decode_cursor to turn into InvalidCursor rather than escaping
    as a 500.
    """
    value = int(raw)
    if not (0 <= value < 2**31):
        raise ValueError("rank cursor value is outside the column's range")
    return value


async def list_page(
    session: AsyncSession, *, user_id: uuid.UUID, limit: int, cursor: Cursor | None
) -> tuple[list[RecommendationItem], str | None]:
    """Keyset pagination over (rank, media_id).

    `rank` is already unique per user, so the id half of the composite is redundant here — kept
    anyway because it costs nothing and matches the shared primitive every other paginated
    endpoint uses.

    `limit + 1` is the has-more probe, the same trick list_entries uses: a COUNT(*) would be a
    second scan to answer a question the extra row already answered.
    """
    seed = aliased(Media)
    statement = (
        select(Recommendation, Media, seed.title)
        .join(Media, Media.id == Recommendation.media_id)
        .join(seed, seed.id == Recommendation.seed_media_id)
        .where(Recommendation.user_id == user_id)
        .order_by(Recommendation.rank.asc(), Recommendation.media_id.asc())
        .limit(limit + 1)
    )
    if cursor is not None:
        statement = statement.where(tuple_(Recommendation.rank, Recommendation.media_id) > (cursor.value, cursor.id))

    rows = (await session.execute(statement)).all()
    has_more = len(rows) > limit
    rows = rows[:limit]

    items = [
        RecommendationItem(
            media=to_persisted(media),
            reason=RecommendationReason(
                seed_media_id=recommendation.seed_media_id,
                seed_title=seed_title,
                matched_genres=list(recommendation.matched_genres),
            ),
        )
        for recommendation, media, seed_title in rows
    ]
    next_cursor = encode_cursor(SORT_KEY, rows[-1][0].rank, rows[-1][0].media_id) if has_more and rows else None
    return items, next_cursor
