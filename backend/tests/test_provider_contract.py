"""One suite every provider must pass, present and future.

A new provider inherits these assertions by appearing in CASES, so shipping one without
behavioural tests is not possible. This is the thing that makes provider #3 cheap.

`test_every_registered_provider_is_covered_by_this_suite` and
`test_each_provider_is_registered_under_its_own_source` close the gap that would otherwise let a
provider land in `build_registry()` without ever landing in `CASES`: those two are the only tests
in this file that read the real registry, rather than constructing a provider directly.
"""

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass

import httpx
import pytest

from app.config import Settings
from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers import build_registry, get_providers, reset_providers
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.base import MediaProvider, ProviderMedia, ProviderSearchPage
from app.media.providers.errors import ProviderRateLimited, ProviderTimeout
from app.media.providers.genres import CANONICAL_GENRES
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from app.media.providers.tmdb.client import TMDBProvider
from tests.fixtures.loader import load_fixture


def _anilist_handler(request: httpx.Request) -> httpx.Response:
    query = json.loads(request.content)["query"]
    if "Page(" in query:
        return httpx.Response(200, json=load_fixture("anilist", "search_page"))
    if "Media(" in query:
        return httpx.Response(200, json=load_fixture("anilist", "media_detail"))
    raise AssertionError(f"unexpected request: {request.url}")


def _tmdb_handler(request: httpx.Request) -> httpx.Response:
    if request.url.path.endswith("/search/tv"):
        return httpx.Response(200, json=load_fixture("tmdb", "search_page"))
    if request.url.path.startswith("/3/tv/"):
        return httpx.Response(200, json=load_fixture("tmdb", "media_detail"))
    raise AssertionError(f"unexpected request: {request.url}")


@dataclass(frozen=True)
class Case:
    name: str
    source: MediaSource
    build: Callable[[ProviderHTTPClient], MediaProvider]
    handler: Callable[[httpx.Request], httpx.Response]
    known_id: str


CASES = [
    Case("anilist", MediaSource.ANILIST, lambda http: AniListProvider(http), _anilist_handler, "21"),
    Case("tmdb", MediaSource.TMDB, lambda http: TMDBProvider(http, api_key="dummy-key"), _tmdb_handler, "71912"),
]


def build(case: Case, handler: Callable[[httpx.Request], httpx.Response] | None = None) -> MediaProvider:
    return case.build(
        ProviderHTTPClient(
            httpx.AsyncClient(transport=httpx.MockTransport(handler or case.handler)),
            RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset"),
        )
    )


def _settings(**overrides: object) -> Settings:
    """Settings built without reading the developer's real .env.

    `Settings` resolves `env_file=".env"` relative to the working directory, so a developer who has
    a TMDB key configured would otherwise get different results from CI.
    """
    base = {
        "database_url": "postgresql+asyncpg://x/y",
        "secret_key": "s",
        "registration_code": "r",
        "tmdb_api_key": "dummy-key",
        "_env_file": None,
    }
    return Settings(**{**base, **overrides})


@pytest.fixture(params=CASES, ids=[case.name for case in CASES])
def case(request: pytest.FixtureRequest) -> Case:
    return request.param


async def test_search_returns_a_well_formed_page(case: Case):
    page = await build(case).search("anything", page=1)

    assert isinstance(page, ProviderSearchPage)
    assert isinstance(page.has_more, bool)
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
    assert any(item.genres for item in page.items), "the recorded fixture must exercise genre mapping"
    for item in page.items:
        assert set(item.genres) <= CANONICAL_GENRES


async def test_get_by_id_returns_a_valid_status(case: Case):
    provider = build(case)
    media = await provider.get_by_id(case.known_id)

    assert isinstance(media, ProviderMedia)
    assert media.ref.source == provider.source
    assert media.ref.external_id == case.known_id
    assert media.type == provider.media_type
    assert isinstance(media.status, MediaStatus)
    assert media.genres, "the recorded fixture must exercise genre mapping"
    assert set(media.genres) <= CANONICAL_GENRES


async def test_get_by_id_returns_none_when_upstream_has_no_such_title(case: Case):
    provider = build(case, lambda request: httpx.Response(404, json={}))
    assert await provider.get_by_id("0") is None


@pytest.mark.parametrize("method", ["search", "get_by_id"])
async def test_rate_limit_raises_with_retry_after(case: Case, method: str):
    provider = build(case, lambda request: httpx.Response(429, headers={"Retry-After": "7"}))
    with pytest.raises(ProviderRateLimited) as excinfo:
        if method == "search":
            await provider.search("anything", page=1)
        else:
            await provider.get_by_id(case.known_id)
    assert excinfo.value.retry_after == 7.0


@pytest.mark.parametrize("method", ["search", "get_by_id"])
async def test_a_hanging_upstream_raises_provider_timeout(case: Case, method: str):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("hung", request=request)

    provider = build(case, handler)
    with pytest.raises(ProviderTimeout):
        if method == "search":
            await provider.search("anything", page=1)
        else:
            await provider.get_by_id(case.known_id)


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


def test_every_registered_provider_is_covered_by_this_suite():
    """The suite's whole premise is that a provider inherits these assertions by being registered.

    Nothing enforced that until now: CASES and build_registry were two hand-maintained lists, so a
    third provider could be registered, inherit no behavioural tests at all, and leave CI green.
    """
    assert set(build_registry(_settings())) == {case.source for case in CASES}


def test_each_provider_is_registered_under_its_own_source():
    """A provider filed under the wrong key would pass every other test in this file, because they
    all construct providers directly rather than reading the registry.
    """
    for source, provider in build_registry(_settings()).items():
        assert source is provider.source


def test_each_provider_gets_its_own_rate_limiter_but_shares_one_client():
    """Limiter state is per-upstream: a shared instance would let AniList's exhausted budget
    throttle TMDB. The connection pool is the opposite — one client is the point.

    Reaching into private attributes is deliberate here; the invariant is real, stated in
    build_registry's docstring, and has no public surface.
    """
    providers = list(build_registry(_settings()).values())
    limiters = [provider._client._limiter for provider in providers]
    clients = [provider._client._client for provider in providers]

    assert len({id(limiter) for limiter in limiters}) == len(providers)
    assert len({id(client) for client in clients}) == 1


def test_tmdb_is_absent_and_warned_about_when_no_api_key_is_configured(caplog: pytest.LogCaptureFixture):
    with caplog.at_level(logging.WARNING):
        registry = build_registry(_settings(tmdb_api_key=None))

    assert set(registry) == {MediaSource.ANILIST}
    assert "TMDB_API_KEY" in caplog.text


def test_get_providers_memoizes_and_reset_providers_clears_it():
    try:
        first = get_providers()
        assert get_providers() is first
        reset_providers()
        assert get_providers() is not first
    finally:
        reset_providers()
