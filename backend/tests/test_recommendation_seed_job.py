from datetime import UTC, datetime, timedelta
from typing import ClassVar

from sqlalchemy import select

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, ProviderMedia
from app.media.providers.errors import ProviderUnavailable
from app.recommendations import service
from app.recommendations.models import MediaSimilarity, RecommendationRun


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


async def _seed_library(session, user_id, *, external_id="1", score="9"):
    """A media row plus a highly-scored library entry pointing at it."""
    media = Media(
        type=MediaType.ANIME,
        source=MediaSource.ANILIST,
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
            status=UserMediaStatus.COMPLETED,
            score=score,
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
    await _seed_library(db_session, auth_user.id, external_id="1")
    provider = SimilarProvider(error=ProviderUnavailable("down"))

    summary = await service.seed_once(db_session, {MediaSource.ANILIST: provider}, now=datetime.now(tz=UTC))

    assert summary.failed == 1
    assert summary.edges == 0


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
