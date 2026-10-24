"""Episode lists from both providers. Payloads follow each API's documented response shape; they
are written out here rather than recorded, so they carry only the fields the mappers read.
"""

import json
from datetime import date

import httpx
import pytest

from app.media.providers.anilist import mapper as anilist_mapper
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.base import ProviderEpisode
from app.media.providers.errors import ProviderUnavailable
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from app.media.providers.tmdb import mapper as tmdb_mapper
from app.media.providers.tmdb.client import TMDBProvider

# 2024-01-05 12:00 UTC and 2024-01-12 12:00 UTC
JAN_5 = 1704456000
JAN_12 = 1705060800


def _http(handler) -> ProviderHTTPClient:
    return ProviderHTTPClient(
        httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset"),
    )


# ---- TMDB


def test_tmdb_seasons_skip_specials_and_come_in_order():
    show = {"seasons": [{"season_number": 2}, {"season_number": 0}, {"season_number": 1}, {"name": "no number"}]}

    assert tmdb_mapper.season_numbers(show) == (1, 2)


def test_tmdb_episodes_carry_titles_and_dates_and_tolerate_gaps():
    season = {
        "episodes": [
            {"episode_number": 1, "name": "Good News About Hell", "air_date": "2022-02-18"},
            {"episode_number": 2, "name": "", "air_date": "2022-02"},
            {"name": "no number"},
        ]
    }

    assert tmdb_mapper.to_episodes(1, season) == (
        ProviderEpisode(season_number=1, number=1, title="Good News About Hell", air_date=date(2022, 2, 18)),
        ProviderEpisode(season_number=1, number=2, title=None, air_date=None),
    )


async def test_tmdb_fetches_each_regular_season_and_never_specials():
    requested: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested.append(request.url.path)
        if request.url.path == "/3/tv/95396":
            return httpx.Response(
                200, json={"seasons": [{"season_number": 0}, {"season_number": 1}, {"season_number": 2}]}
            )
        season = int(request.url.path.rsplit("/", 1)[1])
        return httpx.Response(200, json={"episodes": [{"episode_number": 1, "name": f"S{season}E1", "air_date": None}]})

    episodes = await TMDBProvider(_http(handler), api_key="dummy").get_episodes("95396")

    assert [(e.season_number, e.number, e.title) for e in episodes] == [(1, 1, "S1E1"), (2, 1, "S2E1")]
    assert "/3/tv/95396/season/0" not in requested


async def test_tmdb_unknown_show_is_none():
    episodes = await TMDBProvider(_http(lambda _: httpx.Response(404)), api_key="dummy").get_episodes("1")

    assert episodes is None


async def test_tmdb_a_listed_season_that_404s_is_an_error_not_an_empty_season():
    """Answering without it would delete that season's stored episodes."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/3/tv/1":
            return httpx.Response(200, json={"seasons": [{"season_number": 1}]})
        return httpx.Response(404)

    with pytest.raises(ProviderUnavailable):
        await TMDBProvider(_http(handler), api_key="dummy").get_episodes("1")


# ---- AniList


def test_anilist_episodes_come_from_the_schedule_as_season_one():
    episodes = anilist_mapper.to_episodes(None, [{"episode": 2, "airingAt": JAN_12}, {"episode": 1, "airingAt": JAN_5}])

    assert episodes == (
        ProviderEpisode(season_number=1, number=1, title=None, air_date=date(2024, 1, 5)),
        ProviderEpisode(season_number=1, number=2, title=None, air_date=date(2024, 1, 12)),
    )


def test_anilist_a_known_total_without_a_schedule_gives_undated_episodes():
    """Common for older finished shows, whose schedule is empty."""
    episodes = anilist_mapper.to_episodes(3, [])

    assert [(e.number, e.air_date) for e in episodes] == [(1, None), (2, None), (3, None)]


def test_anilist_the_total_fills_numbers_the_schedule_lacks_and_bad_nodes_are_skipped():
    episodes = anilist_mapper.to_episodes(
        3, [{"episode": 1, "airingAt": JAN_5}, {"episode": None, "airingAt": JAN_12}, {"episode": 0}, "junk"]
    )

    assert [(e.number, e.air_date) for e in episodes] == [(1, date(2024, 1, 5)), (2, None), (3, None)]


async def test_anilist_pages_through_the_schedule():
    pages: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        page = json.loads(request.content)["variables"]["page"]
        pages.append(page)
        nodes = [{"episode": page, "airingAt": JAN_5}]
        connection = {"pageInfo": {"hasNextPage": page < 2}, "nodes": nodes}
        return httpx.Response(200, json={"data": {"Media": {"episodes": 2, "airingSchedule": connection}}})

    episodes = await AniListProvider(_http(handler)).get_episodes("154587")

    assert pages == [1, 2]
    assert [e.number for e in episodes] == [1, 2]


async def test_anilist_unknown_title_is_none():
    assert await AniListProvider(_http(lambda _: httpx.Response(404))).get_episodes("1") is None
    assert await AniListProvider(_http(lambda _: httpx.Response(404))).get_episodes("not-a-number") is None


async def test_anilist_losing_the_title_mid_paging_is_an_error_not_a_partial_list():
    def handler(request: httpx.Request) -> httpx.Response:
        if json.loads(request.content)["variables"]["page"] == 1:
            connection = {"pageInfo": {"hasNextPage": True}, "nodes": [{"episode": 1, "airingAt": JAN_5}]}
            return httpx.Response(200, json={"data": {"Media": {"episodes": None, "airingSchedule": connection}}})
        return httpx.Response(404)

    with pytest.raises(ProviderUnavailable):
        await AniListProvider(_http(handler)).get_episodes("154587")


async def test_anilist_a_schedule_past_the_page_cap_with_no_total_is_an_error():
    def handler(request: httpx.Request) -> httpx.Response:
        connection = {"pageInfo": {"hasNextPage": True}, "nodes": []}
        return httpx.Response(200, json={"data": {"Media": {"episodes": None, "airingSchedule": connection}}})

    with pytest.raises(ProviderUnavailable):
        await AniListProvider(_http(handler)).get_episodes("21")
