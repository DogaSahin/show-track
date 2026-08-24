import logging
import uuid
from collections import defaultdict
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.db import BULK_INSERT_CHUNK_SIZE, chunked, get_sessionmaker
from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource
from app.media.providers.base import MediaProvider, MediaRef
from app.media.providers.errors import ProviderError
from app.media.service import persist_media_bulk
from app.recommendations.models import MediaSimilarity, RecommendationRun
from app.recommendations.schemas import SeedSummary
from app.recommendations.scoring import SCORE_THRESHOLD
from app.sync.locks import SEED_LOCK_KEY, advisory_lock

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
    # COMMIT, not rollback, and the difference only shows up in a caller that shares its session.
    # Rollback here is ROLLBACK TO SAVEPOINT under the test fixture's joined transaction, which
    # discards rows the CALLER wrote before calling in — measured: the seeded media row vanished
    # and the edge insert failed on fk_media_similarity_source_media_id_media. That is the same
    # trap apply_refresh's docstring records. Committing an already-read-only transaction ends it
    # just as completely, leaves the connection idle rather than idle-in-transaction, and cannot
    # destroy work this function did not do.
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
    edges = await _write_edges(session, refs_by_seed, media_ids, now=now)
    await _invalidate(session, list(refs_by_seed))
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
) -> int:
    rows = []
    # Deduped because ON CONFLICT DO UPDATE raises cardinality_violation ("cannot affect row a
    # second time") when ONE statement carries the same conflict key twice — DO NOTHING tolerates
    # it, DO UPDATE does not. Measured: a provider repeating a candidate inside one similar-to
    # list aborted the whole sweep, which is precisely the per-seed isolation this job promises.
    # persist_media_bulk guards the identical hazard for the same reason. First occurrence wins:
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
        return 0

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
    return written


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
