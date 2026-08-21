import uuid
from datetime import UTC, datetime, timedelta
from typing import ClassVar

from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderMedia
from app.media.providers.errors import ProviderRateLimited, ProviderUnavailable
from app.sync import service
from tests.factories import make_media, make_user, make_user_media

FRESH_AIR_DATE = datetime(2026, 12, 1, 15, 0, tzinfo=UTC)
# A fixed "now" for the cadence tests. Passed in rather than read from the clock, for the same
# reason scan_thresholds takes one: a test whose expected counts depend on the wall clock is a
# test that fails on a slow CI runner and nowhere else.
NOW = datetime(2026, 8, 21, 12, 0, tzinfo=UTC)


def _detail(external_id: str, *, source=MediaSource.ANILIST, status=MediaStatus.AIRING, episode=7) -> ProviderMedia:
    return ProviderMedia(
        ref=MediaRef(source=source, external_id=external_id),
        type=MediaType.ANIME,
        title=f"Show {external_id}",
        year=2024,
        genres=("action",),
        cover_image_url=None,
        status=status,
        next_episode=NextEpisode(season_number=1, number=episode, airs_at=FRESH_AIR_DATE),
    )


class BatchProvider(MediaProvider):
    """Answers get_many from a canned mapping, or raises. Counts calls, so a test can prove the
    job batched instead of looping.
    """

    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    def __init__(self, results: dict[str, ProviderMedia] | None = None, error: Exception | None = None) -> None:
        self._results = results or {}
        self._error = error
        self.calls = 0

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str):
        raise AssertionError("the sync job must use get_many, not get_by_id")

    async def get_many(self, external_ids):
        self.calls += 1
        if self._error is not None:
            raise self._error
        wanted = set(external_ids)
        return {k: v for k, v in self._results.items() if k in wanted}


async def _run(db_session, providers, *, now=NOW):
    """The three service steps run_sync composes, minus the transaction boundaries it owns.

    Deliberately NOT one do-everything call: run_sync's rollback() would roll back to this
    fixture's savepoint and discard the seeded rows (measured — a seeded count went 1 -> 0),
    making three tests fail and two pass for the wrong reason.
    """
    worklist = await service.collect_worklist(db_session, now=now)
    fetched, failed_sources = await service.fetch_all(providers, worklist)
    return await service.apply_refresh(db_session, worklist, fetched, failed_sources, now=now)


async def _tracked_media(db_session, **overrides) -> Media:
    """A media row at least one user has in their library — which is what the job syncs."""
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    media = make_media(**{"external_id": tag, **overrides})
    db_session.add_all([user, media])
    await db_session.flush()
    db_session.add(make_user_media(user.id, media.id))
    await db_session.flush()
    return media


async def test_a_stale_air_date_is_refreshed(db_session):
    media = await _tracked_media(
        db_session,
        external_id="1",
        status=MediaStatus.AIRING,
        next_episode_date=datetime(2020, 1, 1, tzinfo=UTC),
        next_episode_number=1,
    )

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({"1": _detail("1")})})

    assert summary.ran is True
    assert (summary.checked, summary.updated, summary.unchanged) == (1, 1, 0)
    await db_session.refresh(media)
    assert media.next_episode_date == FRESH_AIR_DATE
    assert media.next_episode_number == 7


async def test_an_unchanged_row_is_not_counted_as_updated(db_session):
    """`updated` has to mean something changed, or the summary is just a row count."""
    await _tracked_media(
        db_session,
        external_id="1",
        status=MediaStatus.AIRING,
        next_episode_date=FRESH_AIR_DATE,
        next_episode_number=7,
        next_episode_season=1,
    )

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({"1": _detail("1")})})

    assert (summary.updated, summary.unchanged) == (0, 1)


async def test_not_yet_aired_titles_are_polled_and_finished_ones_are_not(db_session):
    """Phase 3's TMDB mapper carries an explicit note that this phase must poll NOT_YET_AIRED and
    skip only FINISHED. Polling only AIRING means a pre-premiere show never starts syncing and its
    first episode never notifies.
    """
    await _tracked_media(db_session, external_id="1", status=MediaStatus.NOT_YET_AIRED)
    await _tracked_media(db_session, external_id="2", status=MediaStatus.FINISHED)

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({"1": _detail("1"), "2": _detail("2")})})

    assert summary.checked == 1


async def test_a_title_in_nobody_s_library_is_not_synced(db_session):
    """DELETE /v1/library leaves the shared media row behind by design, so an unfiltered query
    spends provider budget on titles nobody watches.
    """
    orphan = make_media(external_id="orphan", status=MediaStatus.AIRING)
    db_session.add(orphan)
    await db_session.flush()

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({"orphan": _detail("orphan")})})

    assert summary.checked == 0


async def test_one_provider_failing_does_not_stop_the_other(db_session):
    """Nothing above this catches anything — app/errors.py is registered on the FastAPI app and a
    scheduled job has no request. A provider failure must be a counted outcome, not an exception
    escaping into APScheduler.
    """
    await _tracked_media(db_session, external_id="1", source=MediaSource.ANILIST, status=MediaStatus.AIRING)
    await _tracked_media(
        db_session, external_id="2", source=MediaSource.TMDB, type=MediaType.TV, status=MediaStatus.AIRING
    )
    healthy = BatchProvider({"2": _detail("2", source=MediaSource.TMDB)})

    summary = await _run(
        db_session,
        {
            MediaSource.ANILIST: BatchProvider(error=ProviderUnavailable("down")),
            MediaSource.TMDB: healthy,
        },
    )

    assert summary.failed == 1
    assert summary.updated == 1


async def test_a_rate_limited_provider_is_counted_not_slept_on(db_session):
    """The job abandons that source for the cycle rather than sleeping: the next cycle is six
    hours away, the data is not urgent, and the threshold scan is unaffected either way.
    """
    await _tracked_media(db_session, external_id="1", status=MediaStatus.AIRING)

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider(error=ProviderRateLimited(retry_after=30))})

    assert summary.failed == 1


async def test_a_title_the_provider_no_longer_knows_is_counted_missing(db_session):
    """Verified against the live API: unknown ids are silently omitted from a batch. That is an
    ordinary answer, not an error, and must not be retried in a loop.
    """
    await _tracked_media(db_session, external_id="1", status=MediaStatus.AIRING)

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({})})

    assert (summary.missing, summary.failed) == (1, 0)


async def test_an_unconfigured_source_is_counted_not_raised(db_session):
    """A default local setup has no TMDB_API_KEY, so TMDB is absent from the registry."""
    await _tracked_media(
        db_session, external_id="1", source=MediaSource.TMDB, type=MediaType.TV, status=MediaStatus.AIRING
    )

    summary = await _run(db_session, {})

    assert summary.failed == 1


async def test_the_job_batches_rather_than_looping(db_session):
    """One call to get_many per source, not one per title — the whole reason batching exists."""
    for i in range(3):
        await _tracked_media(db_session, external_id=str(i), status=MediaStatus.AIRING)
    provider = BatchProvider({str(i): _detail(str(i)) for i in range(3)})

    await _run(db_session, {MediaSource.ANILIST: provider})

    assert provider.calls == 1


async def test_an_empty_worklist_is_not_a_provider_request(db_session):
    provider = BatchProvider({})

    summary = await _run(db_session, {MediaSource.ANILIST: provider})

    assert (summary.checked, summary.updated, summary.failed) == (0, 0, 0)
    assert provider.calls == 0


async def test_a_contended_run_reports_ran_false():
    """The property this whole phase exists for, on the entry point the scheduler actually calls.
    advisory_lock is tested in isolation; nothing else covers the branch that USES it.
    """
    from app.sync.locks import SYNC_LOCK_KEY, advisory_lock

    async with advisory_lock(SYNC_LOCK_KEY) as held:
        assert held is True
        summary = await service.run_sync({})

    assert summary.ran is False
    assert summary.checked == 0


async def test_a_title_airing_soon_is_due_sooner_than_a_distant_one(db_session):
    """The whole point of tiering. Both were synced two hours ago; only the imminent one is due.

    A flat interval polls these two at the same rate, which is wrong in both directions at once —
    it wastes provider budget on the distant title and under-samples the one whose date is about
    to drive a notification.
    """
    await _tracked_media(
        db_session,
        external_id="imminent",
        status=MediaStatus.AIRING,
        next_episode_date=NOW + timedelta(hours=12),
        next_episode_number=3,
        last_synced_at=NOW - timedelta(hours=2),
    )
    await _tracked_media(
        db_session,
        external_id="distant",
        status=MediaStatus.AIRING,
        next_episode_date=NOW + timedelta(days=30),
        next_episode_number=1,
        last_synced_at=NOW - timedelta(hours=2),
    )

    worklist = await service.collect_worklist(db_session, now=NOW)

    assert [external_id for _, _, external_id in worklist] == ["imminent"]


async def test_a_never_synced_title_is_due_whatever_its_tier(db_session):
    """NULL is what every row carries the moment the migration lands, and what a title added to a
    library carries until the first sync touches it. Falling into the slowest tier on a NULL would
    idle a brand-new row for 24 hours.
    """
    await _tracked_media(
        db_session,
        external_id="new",
        status=MediaStatus.AIRING,
        next_episode_date=NOW + timedelta(days=90),
        next_episode_number=1,
    )

    worklist = await service.collect_worklist(db_session, now=NOW)

    assert [external_id for _, _, external_id in worklist] == ["new"]


async def test_a_pointer_stuck_in_the_past_is_polled_at_the_tightest_cadence(db_session):
    """An air date that has passed while the status is still AIRING is the strongest signal that
    the pointer is stale — so it belongs in the fastest tier, not the fallback one.

    Falls out of the tier condition having no lower bound, but it is the case a future reader is
    most likely to "tidy up" into a BETWEEN, so it gets a test.
    """
    await _tracked_media(
        db_session,
        external_id="stuck",
        status=MediaStatus.AIRING,
        next_episode_date=NOW - timedelta(days=2),
        next_episode_number=5,
        last_synced_at=NOW - timedelta(hours=2),
    )

    worklist = await service.collect_worklist(db_session, now=NOW)

    assert [external_id for _, _, external_id in worklist] == ["stuck"]


async def test_a_source_level_failure_does_not_start_a_cooldown(db_session):
    """Stamping last_synced_at on a failure would put titles into cooldown BECAUSE the provider
    was down — an outage would then render as "everything looks fresh", which is the one reading
    that makes it invisible.

    Also pins the failed/missing split: a title whose whole source fell over is not a title the
    provider disowned.
    """
    media = await _tracked_media(db_session, external_id="1", status=MediaStatus.AIRING)

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider(error=ProviderUnavailable("down"))})

    assert (summary.failed, summary.missing) == (1, 0)
    await db_session.refresh(media)
    assert media.last_synced_at is None


async def test_a_title_the_provider_disowned_is_stamped_as_answered(db_session):
    """A disowned title still counts as answered, so it starts a cooldown, so it starts a cooldown.

    Without the stamp this is a hot loop: a delisted title keeps its now-past air date, which pins
    it to the tightest tier, which re-fetches it every hour forever.
    """
    media = await _tracked_media(
        db_session,
        external_id="1",
        status=MediaStatus.AIRING,
        next_episode_date=NOW - timedelta(days=2),
        next_episode_number=5,
    )

    summary = await _run(db_session, {MediaSource.ANILIST: BatchProvider({})})

    assert (summary.missing, summary.failed) == (1, 0)
    await db_session.refresh(media)
    assert media.last_synced_at == NOW
