from datetime import UTC, datetime

import httpx
import pytest

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaRef
from app.media.providers.errors import ProviderUnavailable
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from app.media.providers.tmdb import mapper
from app.media.providers.tmdb.client import TMDBProvider
from tests.fixtures.loader import load_fixture


def build_provider(handler) -> TMDBProvider:
    return TMDBProvider(
        ProviderHTTPClient(
            httpx.AsyncClient(transport=httpx.MockTransport(handler)),
            RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset"),
        ),
        api_key="dummy-key",
    )


def test_search_mapper_normalizes_a_page():
    page = mapper.to_search_page(load_fixture("tmdb", "search_page"))

    assert page.has_more is True, "page 1 of 3"
    first = page.items[0]
    assert first.ref == MediaRef(source=MediaSource.TMDB, external_id="1396")
    assert first.type is MediaType.TV
    assert first.title == "Breaking Bad"
    assert first.year == 2008
    assert first.genres == ("crime", "drama")
    assert first.cover_image_url == "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"


def test_search_mapper_handles_empty_air_date_null_poster_and_compound_genres():
    second = mapper.to_search_page(load_fixture("tmdb", "search_page")).items[1]

    assert second.year is None, "TMDB sends an empty string, not null"
    assert second.cover_image_url is None
    assert second.genres == ("action", "adventure", "fantasy", "sci_fi"), "16 dropped, two fanned out"


def test_has_more_is_false_on_the_last_page():
    raw = load_fixture("tmdb", "search_page") | {"page": 3}
    assert mapper.to_search_page(raw).has_more is False


def test_detail_mapper_reads_genre_objects_and_next_episode():
    media = mapper.to_media(load_fixture("tmdb", "media_detail"))

    assert media.status is MediaStatus.AIRING
    assert media.genres == ("action", "adventure", "fantasy", "sci_fi")
    assert media.next_episode is not None
    assert media.next_episode.season_number == 4
    assert media.next_episode.number == 1
    # Fabricated precision: TMDB gives a date only, so the time is midnight UTC.
    assert media.next_episode.airs_at == datetime(2026, 9, 15, 0, 0, tzinfo=UTC)


def test_detail_mapper_returns_none_next_episode_when_absent():
    raw = load_fixture("tmdb", "media_detail") | {"next_episode_to_air": None}
    assert mapper.to_media(raw).next_episode is None


def test_detail_mapper_tolerates_a_malformed_air_date():
    """A mapper is supposed to be pure and total over provider input — a truncated date must
    not raise out of it. The season/episode numbers are still real and worth keeping.
    """
    raw = load_fixture("tmdb", "media_detail") | {
        "next_episode_to_air": {"season_number": 4, "episode_number": 1, "air_date": "2026-09"}
    }
    media = mapper.to_media(raw)
    assert media.next_episode is not None
    assert media.next_episode.airs_at is None


def test_unknown_status_falls_back_to_airing():
    """Prefer the value that keeps Phase 5's sync job polling. A wrong FINISHED is silent and
    permanent; a wrong AIRING costs one wasted request per cycle.
    """
    raw = load_fixture("tmdb", "media_detail") | {"status": "Rumoured"}
    assert mapper.to_media(raw).status is MediaStatus.AIRING


async def test_get_by_id_returns_none_on_404():
    assert await build_provider(lambda request: httpx.Response(404, json={})).get_by_id("0") is None


async def test_search_raises_on_404_instead_of_returning_an_empty_page():
    """TMDB returns 404 for any unrecognized path, not just missing records. A search endpoint
    has no single record to be missing, so a typo'd route or a v3-to-v4 deprecation must surface
    as an outage rather than a permanent, silent zero-results page.
    """
    with pytest.raises(ProviderUnavailable):
        await build_provider(lambda request: httpx.Response(404, json={})).search("witcher", page=1)


async def test_search_raises_on_invalid_key_response_instead_of_returning_an_empty_page():
    """TMDB's invalid-key response is a 401 with a well-formed JSON body, so a page that only
    checks whether the body parses would silently read a revoked key as zero results.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"success": False, "status_code": 7, "status_message": "Invalid API key"})

    with pytest.raises(ProviderUnavailable):
        await build_provider(handler).search("witcher", page=1)


async def test_api_key_is_sent_and_search_params_pass_through():
    captured = {}
    captured_path = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_path
        captured.update(dict(request.url.params))
        captured_path = request.url.path
        return httpx.Response(200, json=load_fixture("tmdb", "search_page"))

    await build_provider(handler).search("witcher", page=2)

    assert captured == {"query": "witcher", "page": "2", "api_key": "dummy-key"}
    assert captured_path == "/3/search/tv", "a wrong path must be visible in the suite, not just in production"
