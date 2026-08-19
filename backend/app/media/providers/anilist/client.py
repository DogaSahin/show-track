from typing import Any, ClassVar

from app.media.models import MediaSource, MediaType
from app.media.providers.anilist import mapper
from app.media.providers.anilist.queries import MEDIA_QUERY, SEARCH_QUERY
from app.media.providers.base import MediaProvider, ProviderMedia, ProviderSearchPage
from app.media.providers.errors import ProviderUnavailable
from app.media.providers.http import ProviderHTTPClient

ANILIST_URL = "https://graphql.anilist.co"
# Pinned to TMDB's fixed page size so both providers advance in lockstep as `page` increments.
PER_PAGE = 20


class AniListProvider(MediaProvider):
    """Anime, from AniList's public GraphQL API. No auth: public data needs none, and this
    project's AniList integration is read-only and one-way, permanently.
    """

    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    def __init__(self, client: ProviderHTTPClient) -> None:
        self._client = client

    async def search(self, query: str, page: int) -> ProviderSearchPage:
        body = await self._post(SEARCH_QUERY, {"search": query, "page": page, "perPage": PER_PAGE})
        if body is None:
            return ProviderSearchPage(items=(), has_more=False)
        return mapper.to_search_page(body["data"]["Page"])

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        try:
            media_id = int(external_id)
        except ValueError:
            # AniList ids are integers. A non-numeric id cannot exist upstream, so this is the
            # same answer as a 404 rather than an error.
            return None
        body = await self._post(MEDIA_QUERY, {"id": media_id})
        if body is None or body.get("data", {}).get("Media") is None:
            return None
        return mapper.to_media(body["data"]["Media"])

    async def _post(self, query: str, variables: dict[str, Any]) -> dict[str, Any] | None:
        """None means "no such record". Anything else is a dict with a populated `data` key."""
        response = await self._client.request("POST", ANILIST_URL, json={"query": query, "variables": variables})
        # 404 is checked BEFORE the errors array, not after: AniList's 404 body also carries an
        # `errors` entry, so the other order would raise where it should return None.
        if response.status_code == 404:
            return None
        body: dict[str, Any] = response.json()
        if body.get("errors"):
            raise ProviderUnavailable(f"AniList returned errors: {body['errors']}")
        return body
