import httpx

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.anilist.client import BATCH_SIZE, AniListProvider
from app.media.providers.base import MediaProvider, MediaRef, ProviderMedia
from app.media.providers.http import ProviderHTTPClient, RateLimiter


def _anilist(handler) -> AniListProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return AniListProvider(ProviderHTTPClient(client, RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset")))


def _media(media_id: int) -> dict:
    return {
        "id": media_id,
        "title": {"romaji": f"Show {media_id}"},
        "genres": [],
        "status": "RELEASING",
        "nextAiringEpisode": {"episode": 3, "airingAt": 1790000000},
    }


class LoopingProvider(MediaProvider):
    """A provider that does NOT override get_many, so it exercises the ABC's default."""

    source = MediaSource.TMDB
    media_type = MediaType.TV

    def __init__(self, known: set[str]) -> None:
        self._known = known
        self.calls: list[str] = []

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        self.calls.append(external_id)
        if external_id not in self._known:
            return None
        return ProviderMedia(
            ref=MediaRef(source=MediaSource.TMDB, external_id=external_id),
            type=MediaType.TV,
            title=f"Show {external_id}",
            year=2020,
            genres=(),
            cover_image_url=None,
            status=MediaStatus.AIRING,
            next_episode=None,
        )


async def test_the_default_loops_get_by_id():
    """Correct for TMDB, whose REST API has no batch endpoint. A concrete default rather than an
    abstract method, so every provider gets a working implementation for free.
    """
    provider = LoopingProvider(known={"1", "2"})

    result = await provider.get_many(["1", "2"])

    assert provider.calls == ["1", "2"]
    assert sorted(result) == ["1", "2"]


async def test_the_default_omits_ids_the_provider_does_not_know():
    """A missing KEY is the answer, matching what get_by_id means by None. Not an exception, and
    not a None value in the mapping — callers should be able to write `if ref in result`.
    """
    provider = LoopingProvider(known={"1"})

    result = await provider.get_many(["1", "404"])

    assert sorted(result) == ["1"]


async def test_anilist_fetches_a_whole_batch_in_one_request():
    """The reason get_many exists. AniList's documented 90/min has been observed degraded to
    30/min, so one request per title is the wrong shape for a job touching every airing title.
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"data": {"Page": {"media": [_media(1), _media(2), _media(3)]}}})

    result = await _anilist(handler).get_many(["1", "2", "3"])

    assert len(requests) == 1
    assert sorted(result) == ["1", "2", "3"]
    assert result["2"].title == "Show 2"


async def test_anilist_keys_by_external_id_not_by_position():
    """Verified against the live API: results come back id-ordered, not request-ordered. A list
    return type would silently attach every title's data to the wrong title.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        # Deliberately reversed relative to the request order.
        return httpx.Response(200, json={"data": {"Page": {"media": [_media(3), _media(1)]}}})

    result = await _anilist(handler).get_many(["1", "3"])

    assert result["1"].title == "Show 1"
    assert result["3"].title == "Show 3"


async def test_anilist_omits_ids_the_provider_does_not_know():
    """Verified against the live API: unknown ids are simply absent from the response."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": {"Page": {"media": [_media(1)]}}})

    result = await _anilist(handler).get_many(["1", "999999"])

    assert sorted(result) == ["1"]


async def test_anilist_chunks_internally_so_callers_need_not_know_the_batch_size():
    """Batch size is provider knowledge; putting it in the caller means every future caller
    re-learns it.
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"data": {"Page": {"media": []}}})

    await _anilist(handler).get_many([str(i) for i in range(BATCH_SIZE + 1)])

    assert len(requests) == 2


async def test_anilist_skips_non_numeric_ids_without_a_request():
    """AniList ids are integers and id_in is typed [Int]. A non-numeric id cannot exist upstream,
    so it is omitted rather than sent — the same judgement get_by_id already makes.
    """
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"data": {"Page": {"media": []}}})

    result = await _anilist(handler).get_many(["not-a-number"])

    assert result == {}
    assert requests == []


async def test_one_malformed_entry_does_not_lose_the_rest_of_the_batch():
    """to_media indexes raw["id"] unguarded, and a KeyError is not a ProviderError — so without
    per-entry containment one bad entry would escape get_many, escape the sync job's per-source
    guard, and roll back every update for every source. In single-fetch shape that cost one title;
    in batch shape it costs the other 49 plus the cycle.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        broken = {"title": {"romaji": "no id"}, "genres": []}
        return httpx.Response(200, json={"data": {"Page": {"media": [_media(1), broken, _media(2)]}}})

    result = await _anilist(handler).get_many(["1", "2", "3"])

    assert sorted(result) == ["1", "2"]
