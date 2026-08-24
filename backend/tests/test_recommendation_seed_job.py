from datetime import UTC, datetime, timedelta
from typing import ClassVar

import pytest
from sqlalchemy import select

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, ProviderMedia
from app.media.providers.errors import ProviderUnavailable
from app.recommendations import service
from app.recommendations.models import MediaSimilarity, Recommendation, RecommendationRun


class SimilarProvider(MediaProvider):
    """Answers fetch_similar and get_many from canned data, or raises."""

    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    def __init__(self, similar=None, details=None, error=None):
        self._similar = similar or {}
        self._details = details or {}
        self._error = error
        self.similar_calls = 0

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str):
        raise AssertionError("not used in these tests")

    async def fetch_similar(self, external_id: str):
        self.similar_calls += 1
        if self._error is not None:
            raise self._error
        return self._similar.get(external_id, ())

    async def get_many(self, external_ids):
        return {eid: self._details[eid] for eid in external_ids if eid in self._details}


def _provider_media(external_id: str, title: str) -> ProviderMedia:
    return ProviderMedia(
        ref=MediaRef(source=MediaSource.ANILIST, external_id=external_id),
        type=MediaType.ANIME,
        title=title,
        year=2020,
        genres=("mecha",),
        cover_image_url=None,
        status=MediaStatus.FINISHED,
        next_episode=None,
    )


async def _seed_library(
    session,
    user_id,
    *,
    external_id="1",
    score="9",
    source=MediaSource.ANILIST,
    status=UserMediaStatus.COMPLETED,
    favorite=False,
):
    """A media row plus, by default, a highly-scored completed library entry pointing at it.

    `status`/`score`/`favorite` are parameters rather than constants because they are exactly the
    three inputs to positive_signal_clause, and a fixture that always satisfies all three cannot
    tell which of them the worklist is actually reading.
    """
    media = Media(
        type=MediaType.ANIME,
        source=source,
        external_id=external_id,
        title=f"seed-{external_id}",
        genres=["mecha"],
        status=MediaStatus.FINISHED,
    )
    session.add(media)
    await session.flush()
    session.add(
        UserMedia(
            user_id=user_id,
            media_id=media.id,
            status=status,
            score=score,
            favorite=favorite,
        )
    )
    await session.flush()
    return media


async def test_seed_writes_edges_and_persists_unknown_candidates(db_session, auth_user):
    seed = await _seed_library(db_session, auth_user.id)
    provider = SimilarProvider(
        similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )
    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.edges == 1
    assert summary.new_media == 1
    edges = (await db_session.scalars(select(MediaSimilarity))).all()
    assert len(edges) == 1
    assert edges[0].source_media_id == seed.id
    assert edges[0].position == 0


async def test_seed_does_not_refetch_detail_for_a_candidate_already_in_media(db_session, auth_user):
    """The dedupe that collapses TMDB's per-candidate cost after the first sweep."""
    await _seed_library(db_session, auth_user.id, external_id="1")
    existing = Media(
        type=MediaType.ANIME,
        source=MediaSource.ANILIST,
        external_id="99",
        title="already here",
        genres=["mecha"],
        status=MediaStatus.FINISHED,
    )
    db_session.add(existing)
    await db_session.flush()

    # details is EMPTY: if the job asks get_many for "99" it will silently write no edge, and the
    # assertion below fails.
    provider = SimilarProvider(similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)})

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.new_media == 0
    assert summary.edges == 1


async def test_a_failing_provider_does_not_abort_the_sweep(db_session, auth_user):
    """THREE seeds, because the guarantee is about the seeds that did NOT fail.

    With a single seed this test passes against an implementation that wraps the whole loop in one
    try/except and returns early — which is precisely the behaviour it exists to forbid. The
    surviving seed's edge is asserted in the table, not just in the summary counters.

    The third seed is TMDB with no TMDB provider registered: that takes the `provider is None`
    branch, which counts `failed` down a different path from ProviderError and would otherwise be
    covered by nothing.
    """
    await _seed_library(db_session, auth_user.id, external_id="1")
    survivor = await _seed_library(db_session, auth_user.id, external_id="2")
    await _seed_library(db_session, auth_user.id, external_id="3", source=MediaSource.TMDB)

    class Flaky(SimilarProvider):
        """A bad minute, not a bad day: one id raises, the rest answer normally."""

        async def fetch_similar(self, external_id):
            self.similar_calls += 1
            if external_id == "1":
                raise ProviderUnavailable("down")
            return self._similar.get(external_id, ())

    provider = Flaky(
        similar={"2": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.seeds == 3
    # One raised, one had no provider at all.
    assert summary.failed == 2
    assert summary.fetched == 1
    assert summary.edges == 1

    edges = (await db_session.scalars(select(MediaSimilarity))).all()
    assert [edge.source_media_id for edge in edges] == [survivor.id], (
        "a provider erroring on one seed must not discard the edges another seed just earned"
    )


async def test_re_seeding_is_idempotent(db_session, auth_user):
    await _seed_library(db_session, auth_user.id, external_id="1")
    provider = SimilarProvider(
        similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )
    now = datetime.now(tz=UTC)

    await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=now)
    await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=now, force=True)

    edges = (await db_session.scalars(select(MediaSimilarity))).all()
    assert len(edges) == 1, "ON CONFLICT should update the edge in place, not duplicate it"


async def test_seed_invalidates_the_cache_of_users_holding_the_seed(db_session, auth_user):
    await _seed_library(db_session, auth_user.id, external_id="1")
    db_session.add(RecommendationRun(user_id=auth_user.id, computed_at=datetime.now(tz=UTC), source_entry_count=1))
    await db_session.flush()
    provider = SimilarProvider(
        similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )

    await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    remaining = (await db_session.scalars(select(RecommendationRun))).all()
    assert remaining == [], "new edges must force the next cursor-less read to recompute"


async def test_a_re_seed_refreshes_the_cadence_clock(db_session, auth_user):
    """The cadence filter, and the ON CONFLICT DO UPDATE that keeps feeding it.

    Neither is covered above: the idempotency test passes force=True precisely to bypass the
    filter. A seed whose edges are older than the cadence is due again — and if the upsert were
    DO NOTHING, fetched_at would never move, so that seed would stay due on every sweep forever
    and the job would re-ask the provider about it for the rest of time.

    A year rather than the configured cadence, so the test does not depend on the developer's
    .env for the interval it steps over.
    """
    await _seed_library(db_session, auth_user.id, external_id="1")
    providers = {
        MediaSource.ANILIST: (
            provider := SimilarProvider(
                similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
                details={"99": _provider_media("99", "candidate")},
            )
        )
    }
    now = datetime.now(tz=UTC)
    later = now + timedelta(days=365)

    await service.seed_once(db_session, providers, now=now)
    await service.seed_once(db_session, providers, now=later)
    third = await service.seed_once(db_session, providers, now=later)

    assert provider.similar_calls == 2, "the second sweep is a year later, so the seed is due again"
    assert third.seeds == 0, "DO NOTHING would leave fetched_at stale and the seed due forever"


async def test_a_provider_repeating_a_candidate_does_not_abort_the_sweep(db_session, auth_user):
    """ON CONFLICT DO UPDATE raises cardinality_violation when one statement carries the same
    conflict key twice, so an upstream list with a repeated id would take down every OTHER seed's
    edges too — the opposite of the per-seed isolation this job promises. Nothing stops a provider
    doing that, and persist_media_bulk already guards the identical hazard.
    """
    await _seed_library(db_session, auth_user.id, external_id="1")
    ref = MediaRef(source=MediaSource.ANILIST, external_id="99")
    provider = SimilarProvider(similar={"1": (ref, ref)}, details={"99": _provider_media("99", "candidate")})

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.edges == 1


async def test_no_transaction_is_held_open_across_any_provider_call(db_session, auth_user):
    """Decision 4-M, asserted rather than assumed — for BOTH provider hops, not just the first.

    A connection left `idle in transaction` across provider HTTP is the objection that rejected an
    advisory lock in get_or_create_media (4-A) and that put a rollback in front of fetch_all. It is
    not tidiness: app/sync/locks.py records that a managed Postgres with
    idle_in_transaction_session_timeout kills such a connection, which here would be mid-sweep,
    after the provider budget has already been spent.

    get_many is the easy one to miss: the known-candidate lookup before it autobegins a
    transaction, and TMDB's get_many is up to 20 sequential requests through the ABC's loop.
    """
    await _seed_library(db_session, auth_user.id, external_id="1")
    seen: dict[str, bool] = {}

    class Watchful(SimilarProvider):
        async def fetch_similar(self, external_id):
            seen["fetch_similar"] = db_session.in_transaction()
            return await super().fetch_similar(external_id)

        async def get_many(self, external_ids):
            seen["get_many"] = db_session.in_transaction()
            return await super().get_many(external_ids)

    provider = Watchful(
        similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.edges == 1, "the sweep must still do its job while holding no transaction"
    assert seen == {"fetch_similar": False, "get_many": False}


async def test_an_unchanged_answer_leaves_the_cache_header_alone(db_session, auth_user):
    """The assertion that makes recommendations_ttl_hours a real setting.

    Invalidating on every ANSWER rather than every CHANGE deletes every active user's
    recommendation_run on every sweep, so the 24h TTL can never expire anything for a user whose
    library holds a due seed — a shipped setting that is inert. force=True, so the sweep genuinely
    re-asks and re-writes rather than being skipped by the cadence filter; the answer is byte
    identical, so nothing about the stored edges moves.
    """
    await _seed_library(db_session, auth_user.id, external_id="1")
    provider = SimilarProvider(
        similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)},
        details={"99": _provider_media("99", "candidate")},
    )
    now = datetime.now(tz=UTC)
    await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=now)

    db_session.add(RecommendationRun(user_id=auth_user.id, computed_at=now, source_entry_count=1))
    await db_session.flush()

    await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=now, force=True)

    remaining = (await db_session.scalars(select(RecommendationRun))).all()
    assert len(remaining) == 1, "an unchanged answer must not drop the cache header"


@pytest.mark.parametrize(
    ("status", "score", "favorite", "expected_seeds"),
    [
        (UserMediaStatus.COMPLETED, None, False, 1),
        (UserMediaStatus.WATCHING, "9", False, 1),
        (UserMediaStatus.WATCHING, None, True, 1),
        (UserMediaStatus.WATCHING, "4", False, 0),
    ],
    ids=["completed", "scored-at-or-above-threshold", "favourited", "none-of-the-three"],
)
async def test_only_a_positive_signal_becomes_a_seed(db_session, auth_user, status, score, favorite, expected_seeds):
    """One case per disjunct of positive_signal_clause, plus the case that satisfies none.

    Every other fixture in this file sets COMPLETED *and* a score of 9, so deleting any single
    disjunct leaves them all green. These four fail individually instead — which matters because
    this clause is the only thing keeping the seed worklist and the taste profile from drifting
    apart. If they drift, the job spends provider calls on titles that then score zero.
    """
    await _seed_library(db_session, auth_user.id, external_id="1", status=status, score=score, favorite=favorite)
    provider = SimilarProvider(similar={"1": (MediaRef(source=MediaSource.ANILIST, external_id="99"),)})

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.seeds == expected_seeds
    assert provider.similar_calls == expected_seeds


async def test_recompute_excludes_titles_already_in_the_library(db_session, auth_user):
    seed = await _seed_library(db_session, auth_user.id, external_id="1")
    owned = await _seed_library(db_session, auth_user.id, external_id="2", score="8")
    db_session.add(
        MediaSimilarity(
            source_media_id=seed.id,
            similar_media_id=owned.id,
            position=0,
            fetched_at=datetime.now(tz=UTC),
        )
    )
    await db_session.flush()

    await service.recompute(db_session, user_id=auth_user.id, now=datetime.now(tz=UTC))

    rows = (await db_session.scalars(select(Recommendation))).all()
    assert rows == [], "a title you already track is not a recommendation"


async def test_recompute_writes_a_run_row_even_when_there_is_nothing_to_recommend(db_session, auth_user):
    """Without this the cold-start user recomputes on every single request, forever."""
    await service.recompute(db_session, user_id=auth_user.id, now=datetime.now(tz=UTC))

    run = await db_session.get(RecommendationRun, auth_user.id)
    assert run is not None
    assert run.source_entry_count == 0


async def test_a_deleted_library_entry_makes_the_cache_stale(db_session, auth_user):
    """max(updated_at) cannot see a removal — this is the source_entry_count path specifically."""
    seed = await _seed_library(db_session, auth_user.id, external_id="1")
    now = datetime.now(tz=UTC)
    await service.recompute(db_session, user_id=auth_user.id, now=now)
    assert not await service.is_stale(db_session, user_id=auth_user.id, now=now)

    entry = await db_session.scalar(select(UserMedia).where(UserMedia.media_id == seed.id))
    await db_session.delete(entry)
    await db_session.flush()

    assert await service.is_stale(db_session, user_id=auth_user.id, now=now)


async def test_rating_a_title_makes_the_cache_stale(db_session, auth_user):
    """The max(updated_at) path, with the not-stale control that isolates it.

    `updated_at` is dated explicitly rather than left to onupdate=func.now(), which renders
    Postgres `now()` — transaction_timestamp(), frozen for the life of a transaction. In
    production the PATCH that rates a title and the later read are two transactions, so it lands
    strictly later; inside this fixture's single external transaction it cannot move (measured:
    the value before the flush, after it, and `SELECT now()` were all identical). Setting it here
    reproduces what production writes, and the score change is kept so the row genuinely changes.
    """
    seed = await _seed_library(db_session, auth_user.id, external_id="1", score=None)
    now = datetime.now(tz=UTC)
    await service.recompute(db_session, user_id=auth_user.id, now=now)
    # The control: without it an is_stale that simply returned True would pass this test.
    assert not await service.is_stale(db_session, user_id=auth_user.id, now=now)

    entry = await db_session.scalar(select(UserMedia).where(UserMedia.media_id == seed.id))
    entry.score = 9
    entry.updated_at = now + timedelta(minutes=1)
    await db_session.flush()

    assert await service.is_stale(db_session, user_id=auth_user.id, now=now)


async def test_ensure_fresh_recomputes_once_and_then_leaves_the_cache_alone(db_session, auth_user):
    """Rebuild when stale, no-op when not — the read path's whole cost model (decision 7-C).

    Also the only exercise of the pg_try_advisory_xact_lock statement: a typo inside that text()
    is invisible until it actually runs.
    """
    seed = await _seed_library(db_session, auth_user.id, external_id="1")
    candidate = Media(
        type=MediaType.ANIME,
        source=MediaSource.ANILIST,
        external_id="99",
        title="candidate",
        genres=["mecha"],
        status=MediaStatus.FINISHED,
    )
    db_session.add(candidate)
    await db_session.flush()
    db_session.add(
        MediaSimilarity(
            source_media_id=seed.id,
            similar_media_id=candidate.id,
            position=0,
            fetched_at=datetime.now(tz=UTC),
        )
    )
    await db_session.flush()

    now = datetime.now(tz=UTC)
    await service.ensure_fresh(db_session, user_id=auth_user.id, now=now)

    rows = (await db_session.scalars(select(Recommendation))).all()
    assert [(row.rank, row.media_id, row.seed_media_id) for row in rows] == [(0, candidate.id, seed.id)]
    run = await db_session.get(RecommendationRun, auth_user.id)
    assert run is not None
    assert run.computed_at == now

    # Nothing has changed, so the second call must not rebuild. Asserted on computed_at rather
    # than on the rows, because a recompute is idempotent and would leave the rows identical.
    await service.ensure_fresh(db_session, user_id=auth_user.id, now=now + timedelta(minutes=5))
    await db_session.refresh(run)
    assert run.computed_at == now
