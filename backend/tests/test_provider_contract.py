"""One suite every provider must pass, present and future.

A new provider inherits these ten assertions by appearing in CASES, so shipping one without
behavioural tests is not possible. This is the thing that makes provider #3 cheap.
"""

import json
from collections.abc import Callable
from dataclasses import dataclass

import httpx
import pytest

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.base import MediaProvider, ProviderMedia, ProviderSearchPage
from app.media.providers.errors import ProviderRateLimited, ProviderTimeout
from app.media.providers.genres import CANONICAL_GENRES
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from app.media.providers.tmdb.client import TMDBProvider
from tests.fixtures.loader import load_fixture


def _anilist_handler(request: httpx.Request) -> httpx.Response:
    query = json.loads(request.content)["query"]
    name = "search_page" if "Page(" in query else "media_detail"
    return httpx.Response(200, json=load_fixture("anilist", name))


def _tmdb_handler(request: httpx.Request) -> httpx.Response:
    name = "search_page" if request.url.path.endswith("/search/tv") else "media_detail"
    return httpx.Response(200, json=load_fixture("tmdb", name))


@dataclass(frozen=True)
class Case:
    name: str
    build: Callable[[ProviderHTTPClient], MediaProvider]
    handler: Callable[[httpx.Request], httpx.Response]
    known_id: str


CASES = [
    Case("anilist", lambda http: AniListProvider(http), _anilist_handler, "21"),
    Case("tmdb", lambda http: TMDBProvider(http, api_key="dummy-key"), _tmdb_handler, "71912"),
]


def build(case: Case, handler: Callable[[httpx.Request], httpx.Response] | None = None) -> MediaProvider:
    return case.build(
        ProviderHTTPClient(
            httpx.AsyncClient(transport=httpx.MockTransport(handler or case.handler)),
            RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset"),
        )
    )


@pytest.fixture(params=CASES, ids=[case.name for case in CASES])
def case(request: pytest.FixtureRequest) -> Case:
    return request.param


async def test_search_returns_a_well_formed_page(case: Case):
    page = await build(case).search("anything", page=1)

    assert isinstance(page, ProviderSearchPage)
    assert page.items, "the recorded fixture must contain at least one result"
    for item in page.items:
        assert item.ref.external_id
        assert item.title
        assert isinstance(item.type, MediaType)


async def test_every_result_is_tagged_with_the_providers_own_source(case: Case):
    provider = build(case)
    page = await provider.search("anything", page=1)
    assert {item.ref.source for item in page.items} == {provider.source}


async def test_every_genre_is_canonical(case: Case):
    page = await build(case).search("anything", page=1)
    for item in page.items:
        assert set(item.genres) <= CANONICAL_GENRES


async def test_get_by_id_returns_a_valid_status(case: Case):
    media = await build(case).get_by_id(case.known_id)

    assert isinstance(media, ProviderMedia)
    assert isinstance(media.status, MediaStatus)
    assert set(media.genres) <= CANONICAL_GENRES


async def test_get_by_id_returns_none_when_upstream_has_no_such_title(case: Case):
    provider = build(case, lambda request: httpx.Response(404, json={}))
    assert await provider.get_by_id("0") is None


async def test_rate_limit_raises_with_retry_after(case: Case):
    provider = build(case, lambda request: httpx.Response(429, headers={"Retry-After": "7"}))
    with pytest.raises(ProviderRateLimited) as excinfo:
        await provider.search("anything", page=1)
    assert excinfo.value.retry_after == 7.0


async def test_a_hanging_upstream_raises_provider_timeout(case: Case):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("hung", request=request)

    with pytest.raises(ProviderTimeout):
        await build(case, handler).search("anything", page=1)


async def test_provider_declares_its_identity_as_real_class_attributes(case: Case):
    """A bare `ClassVar` annotation with no value creates NO class attribute.

    @abstractmethod makes a subclass missing `search()` un-instantiable, but a subclass missing
    `source` instantiates perfectly well and then fails with AttributeError at first access —
    inside the registry, far from the class that omitted it. The ABC cannot enforce this; this
    test is the enforcement.
    """
    provider = build(case)

    assert isinstance(provider.source, MediaSource)
    assert isinstance(provider.media_type, MediaType)


async def test_every_result_matches_the_providers_declared_media_type(case: Case):
    """`media_type` is a second source of truth alongside each item's own `type`.

    They are independently assigned, so they can disagree — and any downstream code trusting one
    rather than the other would then read a different answer. This is also the declaration a third
    provider is most likely to break, since it encodes "one provider serves exactly one media type".
    """
    page = await build(case).search("anything", page=1)

    assert {item.type for item in page.items} == {build(case).media_type}


async def test_next_episode_timestamps_are_timezone_aware(case: Case):
    """ "tz-aware" is a comment on NextEpisode.airs_at, not something the type system enforces.

    A naive datetime survives construction and surfaces much later as
    `TypeError: can't compare offset-naive and offset-aware datetimes`, deep inside Phase 5's sync
    job. Both recorded detail fixtures carry a next episode, so this asserts non-vacuously — if a
    fixture is ever replaced with one that has no next episode, this test failing is correct and
    the fixture is what needs fixing.
    """
    media = await build(case).get_by_id(case.known_id)

    assert media.next_episode is not None, "both recorded detail fixtures carry a next episode"
    assert media.next_episode.airs_at is not None
    assert media.next_episode.airs_at.tzinfo is not None
