from app.media.models import MediaSource
from app.media.providers.anilist.client import AniListProvider
from app.media.providers.base import SIMILAR_LIMIT
from app.media.providers.tmdb.client import TMDBProvider


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload


class RecordingClient:
    """Stands in for ProviderHTTPClient. Records the last request, answers from a queue."""

    def __init__(self, *payloads):
        self._payloads = list(payloads)
        self.calls = []

    async def request(self, method, url, **kwargs):
        self.calls.append((method, url, kwargs))
        return FakeResponse(self._payloads.pop(0))


def _anilist_body(*ids, include_null=False):
    nodes = [{"mediaRecommendation": {"id": i}} for i in ids]
    if include_null:
        # AniList returns a null mediaRecommendation when the suggested title was deleted.
        nodes.insert(0, {"mediaRecommendation": None})
    return {"data": {"Media": {"recommendations": {"nodes": nodes}}}}


async def test_anilist_fetch_similar_preserves_upstream_order():
    client = RecordingClient(_anilist_body(30, 10, 20))
    provider = AniListProvider(client)

    refs = await provider.fetch_similar("1")

    assert [r.external_id for r in refs] == ["30", "10", "20"]
    assert {r.source for r in refs} == {MediaSource.ANILIST}


async def test_anilist_fetch_similar_skips_deleted_recommendations():
    client = RecordingClient(_anilist_body(7, include_null=True))
    provider = AniListProvider(client)

    refs = await provider.fetch_similar("1")

    assert [r.external_id for r in refs] == ["7"]


async def test_anilist_fetch_similar_returns_empty_for_non_numeric_id():
    client = RecordingClient()
    provider = AniListProvider(client)

    assert await provider.fetch_similar("not-a-number") == ()
    assert client.calls == [], "a non-numeric id cannot exist upstream; do not spend a request"


async def test_tmdb_fetch_similar_caps_at_the_page_limit():
    payload = {"results": [{"id": n} for n in range(SIMILAR_LIMIT + 5)]}
    provider = TMDBProvider(RecordingClient(payload), "key")

    refs = await provider.fetch_similar("42")

    assert len(refs) == SIMILAR_LIMIT
    assert [r.external_id for r in refs] == [str(n) for n in range(SIMILAR_LIMIT)]
    assert {r.source for r in refs} == {MediaSource.TMDB}


class NotFoundClient:
    async def request(self, method, url, **kwargs):
        return FakeResponse({}, status_code=404)


async def test_tmdb_fetch_similar_returns_empty_on_404():
    """A 404 here DOES mean "no such record" — the path names one show — so it is an empty
    answer, not the outage that the same status means on /search/tv."""
    provider = TMDBProvider(NotFoundClient(), "key")

    assert await provider.fetch_similar("42") == ()
