from collections.abc import AsyncIterator, Callable, Iterator

import httpx
import pytest
from httpx import AsyncClient

from app.http import get_http_client
from main import app

Handler = Callable[[httpx.Request], httpx.Response]

POSTER_PATH = "/v1/images/tmdb/w500/abc123.jpg"


@pytest.fixture
def upstream() -> Iterator[Callable[[Handler], list[httpx.Request]]]:
    """Point the relay at a mock TMDB and hand back the list of requests it received.

    Overrides get_http_client rather than patching the module global, the same way conftest.py
    overrides get_session: the route takes the client as a dependency for exactly this reason.
    """

    def install(handler: Handler) -> list[httpx.Request]:
        seen: list[httpx.Request] = []

        def recording(request: httpx.Request) -> httpx.Response:
            seen.append(request)
            return handler(request)

        mock = httpx.AsyncClient(transport=httpx.MockTransport(recording))
        app.dependency_overrides[get_http_client] = lambda: mock
        return seen

    yield install
    app.dependency_overrides.pop(get_http_client, None)


def _jpeg(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, headers={"Content-Type": "image/jpeg"}, content=b"img")


async def test_relays_poster_with_cache_header(client: AsyncClient, upstream) -> None:
    seen = upstream(_jpeg)

    response = await client.get(POSTER_PATH)

    assert response.status_code == 200
    assert response.content == b"img"
    assert response.headers["content-type"].startswith("image/jpeg")
    assert response.headers["cache-control"] == "public, max-age=2592000, immutable"
    assert [str(r.url) for r in seen] == ["https://image.tmdb.org/t/p/w500/abc123.jpg"]


async def test_needs_no_token(client: AsyncClient, upstream) -> None:
    """The plain `client` carries no bearer token, and the relay must not invent one upstream."""
    seen = upstream(_jpeg)

    response = await client.get(POSTER_PATH)

    assert response.status_code == 200
    assert "authorization" not in seen[0].headers


@pytest.mark.parametrize(
    "path",
    [
        "/v1/images/tmdb/original/abc.jpg",
        "/v1/images/tmdb/w500/abc.gif",
        "/v1/images/tmdb/w500/a-b.jpg",
        "/v1/images/tmdb/w500/..%2Fetc.jpg",
        "/v1/images/tmdb/w500/%2e%2e.jpg",
        "/v1/images/tmdb/w500/abc.jpg%3Fx=1",
        "/v1/images/tmdb/w500/" + "a" * 65 + ".jpg",
    ],
)
async def test_rejects_bad_paths_without_upstream_call(client: AsyncClient, upstream, path: str) -> None:
    seen = upstream(_jpeg)

    response = await client.get(path)

    assert response.status_code == 404
    assert seen == []


async def test_upstream_404_is_404(client: AsyncClient, upstream) -> None:
    upstream(lambda request: httpx.Response(404))

    response = await client.get(POSTER_PATH)

    assert response.status_code == 404


async def test_upstream_redirect_is_502(client: AsyncClient, upstream) -> None:
    """A redirect is never followed: the relay fetches TMDB's URL or nothing."""
    seen = upstream(lambda request: httpx.Response(302, headers={"Location": "https://evil.example/x"}))

    response = await client.get(POSTER_PATH)

    assert response.status_code == 502
    assert len(seen) == 1


async def test_upstream_500_is_502(client: AsyncClient, upstream) -> None:
    upstream(lambda request: httpx.Response(500))

    response = await client.get(POSTER_PATH)

    assert response.status_code == 502


async def test_timeout_is_502(client: AsyncClient, upstream) -> None:
    def stall(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timed out", request=request)

    upstream(stall)

    response = await client.get(POSTER_PATH)

    assert response.status_code == 502


async def test_wrong_content_type_is_502(client: AsyncClient, upstream) -> None:
    upstream(lambda request: httpx.Response(200, headers={"Content-Type": "text/html"}, content=b"<html>"))

    response = await client.get(POSTER_PATH)

    assert response.status_code == 502


class _Chunks(httpx.AsyncByteStream):
    """A body with no Content-Length, so only counting the bytes as they arrive can cap it."""

    def __init__(self, total: int) -> None:
        self._total = total

    async def __aiter__(self) -> AsyncIterator[bytes]:
        chunk = 64 * 1024
        sent = 0
        while sent < self._total:
            size = min(chunk, self._total - sent)
            sent += size
            yield b"x" * size


async def test_oversize_without_length_is_502(client: AsyncClient, upstream) -> None:
    oversize = 5 * 1024 * 1024 + 1
    upstream(lambda request: httpx.Response(200, headers={"Content-Type": "image/jpeg"}, stream=_Chunks(oversize)))

    response = await client.get(POSTER_PATH)

    assert response.status_code == 502
