from typing import Any, ClassVar

from app.media.models import MediaSource, MediaType
from app.media.providers.base import MediaProvider, ProviderMedia, ProviderSearchPage
from app.media.providers.http import ProviderHTTPClient
from app.media.providers.tmdb import mapper

TMDB_BASE_URL = "https://api.themoviedb.org/3"


class TMDBProvider(MediaProvider):
    """TV shows, from TMDB's REST API.

    The v3 API key travels as a query parameter, which is what TMDB's free key supports. That
    puts a credential in the request URL, so **never log a full TMDB request URL** — strip the
    query before logging, or log only the path.
    """

    source: ClassVar[MediaSource] = MediaSource.TMDB
    media_type: ClassVar[MediaType] = MediaType.TV

    def __init__(self, client: ProviderHTTPClient, api_key: str) -> None:
        self._client = client
        self._api_key = api_key

    async def search(self, query: str, page: int) -> ProviderSearchPage:
        raw = await self._get("/search/tv", {"query": query, "page": page})
        if raw is None:
            return ProviderSearchPage(items=(), has_more=False)
        return mapper.to_search_page(raw)

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        raw = await self._get(f"/tv/{external_id}", {})
        return mapper.to_media(raw) if raw is not None else None

    async def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any] | None:
        response = await self._client.request(
            "GET", f"{TMDB_BASE_URL}{path}", params={**params, "api_key": self._api_key}
        )
        if response.status_code == 404:
            return None
        data: dict[str, Any] = response.json()
        return data
