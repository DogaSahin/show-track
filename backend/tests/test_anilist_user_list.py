import httpx
import pytest

from app.media.providers.anilist.client import MAX_LIST_CHUNKS, AniListProvider
from app.media.providers.errors import ProviderUnavailable, UserListNotAvailable
from app.media.providers.http import ProviderHTTPClient, RateLimiter
from tests.fixtures.loader import load_fixture


def _provider(handler) -> AniListProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return AniListProvider(ProviderHTTPClient(client, RateLimiter("X-RateLimit-Remaining", "X-RateLimit-Reset")))


def _responder(*bodies: dict):
    """Answers each successive request with the next body, so a chunk loop can be driven."""
    remaining = list(bodies)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=remaining.pop(0) if remaining else bodies[-1])

    return handler


def _chunk(has_next: bool, media_id: int) -> dict:
    return {
        "data": {
            "MediaListCollection": {
                "hasNextChunk": has_next,
                "lists": [
                    {
                        "name": "Watching",
                        "entries": [
                            {
                                "status": "CURRENT",
                                "score": 7.0,
                                "progress": 1,
                                "media": {
                                    "id": media_id,
                                    "title": {"romaji": f"Show {media_id}"},
                                    "genres": [],
                                    "status": "FINISHED",
                                },
                            }
                        ],
                    }
                ],
            }
        }
    }


async def test_a_single_chunk_list_is_parsed():
    provider = _provider(_responder(load_fixture("anilist", "user_list")))

    result = await provider.fetch_user_list("someone")

    assert [entry.media.ref.external_id for entry in result.entries] == ["154587", "16498"]
    assert result.dropped == 0
    assert result.truncated is False


async def test_a_chunked_list_is_fully_consumed():
    """One GraphQL query returns the whole list with each entry's media inline, so a 400-title
    list is one request — but hasNextChunk exists for lists past perChunk, and stopping at the
    first chunk would silently import a prefix.
    """
    provider = _provider(_responder(_chunk(True, 1), _chunk(True, 2), _chunk(False, 3)))

    result = await provider.fetch_user_list("someone")

    assert [entry.media.ref.external_id for entry in result.entries] == ["1", "2", "3"]
    assert result.truncated is False


async def test_a_provider_that_never_stops_chunking_is_bounded():
    """A liveness guard, not a scale limit: an upstream that always answers hasNextChunk: true
    must not spin forever inside a request.

    The assertion counts REQUESTS. Asserting on the entries would pass just as well if the loop
    broke after chunk one — and if the bound were ever removed, this test would not fail, it
    would hang pytest.
    """
    calls = 0

    def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=_chunk(True, 1))

    result = await _provider(handler).fetch_user_list("someone")

    assert calls == MAX_LIST_CHUNKS
    assert result.truncated is True


async def test_a_nonexistent_user_is_user_list_not_available():
    """Verified against the live API: AniList answers a missing username with HTTP 404 and
    `{"errors": [{"message": "User not found", "status": 404}], "data": {"MediaListCollection": null}}`.
    The shared transport returns 404 rather than raising, so this must become a 4xx about the
    request, not a 502 about the upstream.
    """
    provider = _provider(
        lambda request: httpx.Response(
            404,
            json={"errors": [{"message": "User not found", "status": 404}], "data": {"MediaListCollection": None}},
        )
    )

    with pytest.raises(UserListNotAvailable):
        await provider.fetch_user_list("ghost")


async def test_a_graphql_error_naming_a_404_is_user_list_not_available():
    """The unverified half: a private profile might come back as 200-with-errors rather than a
    bare 404. Classified on AniList's per-error `status` INTEGER — which the live probe confirmed
    exists — not on the prose of `message`, because substring-matching would reclassify any
    unrelated error containing the word into a 404 about the username.
    """
    provider = _provider(
        lambda request: httpx.Response(200, json={"errors": [{"message": "Private User", "status": 404}], "data": None})
    )

    with pytest.raises(UserListNotAvailable):
        await provider.fetch_user_list("secretive")


async def test_an_unrelated_graphql_error_still_raises_provider_unavailable():
    """Only missing/private users are re-classified. Everything else stays a 502, or the
    re-classification would turn real outages into 404s.
    """
    provider = _provider(
        lambda request: httpx.Response(
            200, json={"errors": [{"message": "Internal Server Error", "status": 500}], "data": None}
        )
    )

    # NOT `pytest.raises(ProviderUnavailable)` with an inner isinstance check:
    # UserListNotAvailable extends ProviderError, not ProviderUnavailable, so that form could
    # never have caught it and the inner assertion would be dead code.
    with pytest.raises(ProviderUnavailable):
        await provider.fetch_user_list("someone")


async def test_a_null_collection_past_chunk_one_ends_the_loop_cleanly():
    """Only chunk 1 can mean "no such list". Past the end of a list, a null collection is the
    ordinary "you asked for more than exists" answer, and 404-ing there would discard chunk 1's
    successfully-read entries and report a readable profile as missing.
    """
    provider = _provider(_responder(_chunk(True, 1), {"data": {"MediaListCollection": None}}))

    result = await provider.fetch_user_list("someone")

    assert [entry.media.ref.external_id for entry in result.entries] == ["1"]


async def test_a_public_profile_with_an_empty_list_is_not_an_error():
    """`lists: []` is a legitimate answer for a public profile with no anime tracked. Treating it
    as UserListNotAvailable would 404 a perfectly readable account.
    """
    provider = _provider(_responder({"data": {"MediaListCollection": {"hasNextChunk": False, "lists": []}}}))

    result = await provider.fetch_user_list("someone")

    assert result.entries == ()
    assert result.dropped == 0
