import json
from datetime import UTC, datetime

import httpx
import pytest

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.anilist import mapper
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.errors import ProviderUnavailable
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from tests.fixtures.loader import load_fixture


def build_provider(handler) -> AniListProvider:
    return AniListProvider(
        ProviderHTTPClient(
            httpx.AsyncClient(transport=httpx.MockTransport(handler)),
            RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset"),
        )
    )


def test_search_mapper_normalizes_a_page():
    page = mapper.to_search_page(load_fixture("anilist", "search_page")["data"]["Page"])

    assert page.has_more is True
    first = page.items[0]
    assert first.ref.source is MediaSource.ANILIST
    assert first.ref.external_id == "16498"
    assert first.type is MediaType.ANIME
    assert first.title == "Attack on Titan"
    assert first.year == 2013
    assert first.genres == ("action", "drama", "fantasy")


def test_search_mapper_survives_null_english_title_year_and_cover():
    page = mapper.to_search_page(load_fixture("anilist", "search_page")["data"]["Page"])
    second = page.items[1]

    assert second.title == "Sousou no Frieren", "romaji is the fallback when english is null"
    assert second.year is None
    assert second.cover_image_url is None


def test_detail_mapper_maps_status_and_next_episode():
    media = mapper.to_media(load_fixture("anilist", "media_detail")["data"]["Media"])

    assert media.status is MediaStatus.AIRING
    assert media.next_episode is not None
    assert media.next_episode.number == 1122
    assert media.next_episode.season_number == 1, "AniList models a cour, so there is one season"
    assert media.next_episode.airs_at == datetime(2025, 8, 18, 7, 0, tzinfo=UTC)


def test_detail_mapper_returns_none_next_episode_when_absent():
    raw = load_fixture("anilist", "media_detail")["data"]["Media"] | {"nextAiringEpisode": None}
    assert mapper.to_media(raw).next_episode is None


def test_hiatus_maps_to_airing_not_finished():
    """FINISHED is what Phase 5's sync job filters out. A paused show marked FINISHED would
    never be polled again, so it would never be seen resuming.
    """
    raw = load_fixture("anilist", "media_detail")["data"]["Media"] | {"status": "HIATUS"}
    assert mapper.to_media(raw).status is MediaStatus.AIRING


async def test_graphql_error_body_with_http_200_raises():
    """GraphQL signals application errors in the body, not the status line. A client that reads
    only status_code parses a failed query as an empty result set.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"errors": [{"message": "Invalid token"}]})

    with pytest.raises(ProviderUnavailable):
        await build_provider(handler).search("frieren", page=1)


async def test_get_by_id_returns_none_on_404():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"errors": [{"message": "Not Found"}]})

    assert await build_provider(handler).get_by_id("999999") is None


async def test_search_query_filters_adult_titles_and_pins_per_page():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=load_fixture("anilist", "search_page"))

    await build_provider(handler).search("frieren", page=2)

    assert "isAdult: false" in captured["query"]
    assert captured["variables"] == {"search": "frieren", "page": 2, "perPage": 20}
