import logging
from typing import Any, ClassVar

from app.media.models import MediaSource, MediaType
from app.media.providers.anilist import mapper
from app.media.providers.anilist.errors import AniListGraphQLError
from app.media.providers.anilist.queries import MEDIA_QUERY, SEARCH_QUERY, USER_LIST_QUERY
from app.media.providers.base import (
    MediaProvider,
    ProviderListEntry,
    ProviderMedia,
    ProviderSearchPage,
    ProviderUserList,
)
from app.media.providers.errors import ProviderUnavailable, UserListNotAvailable
from app.media.providers.http import ProviderHTTPClient

logger = logging.getLogger(__name__)

ANILIST_URL = "https://graphql.anilist.co"
# Pinned to TMDB's fixed page size so both providers advance in lockstep as `page` increments.
PER_PAGE = 20
# AniList's documented per-chunk maximum. A 400-title list is one request; chunking exists for
# the long tail.
PER_CHUNK = 500
# Liveness guard, not a scale limit: an upstream that always answers hasNextChunk: true must not
# spin forever inside a request. 20 x 500 bounds a synchronous import at 10,000 entries.
MAX_LIST_CHUNKS = 20


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
            # Unlike get_by_id, a 404 here has no "no such record" reading — a search endpoint
            # has no single record to be missing. It means the endpoint moved or an edge refused
            # the request, so returning an empty page would hide that as a permanent, silent
            # zero-results answer instead of surfacing it as the outage it is.
            raise ProviderUnavailable("AniList search endpoint returned 404")
        raw_page = body["data"].get("Page")
        if not isinstance(raw_page, dict):
            # Same reasoning as the 404 above: a body with no Page object is not "zero results",
            # it is a response we cannot read, and mapping it would answer with a silent empty
            # page instead of the failure it is.
            raise ProviderUnavailable("AniList search response carried no Page object")
        return mapper.to_search_page(raw_page)

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        try:
            media_id = int(external_id)
        except ValueError:
            # AniList ids are integers. A non-numeric id cannot exist upstream, so this is the
            # same answer as a 404 rather than an error.
            return None
        body = await self._post(MEDIA_QUERY, {"id": media_id})
        if body is None:
            return None
        # `data` is guaranteed to be an object by _post, so this reads it the same way search()
        # does. AniList answers a missing title with a 404, but `data: {"Media": null}` is the
        # documented GraphQL shape for the same thing and costs nothing to honour.
        raw_media = body["data"].get("Media")
        if not isinstance(raw_media, dict):
            return None
        return mapper.to_media(raw_media)

    async def fetch_user_list(self, username: str) -> ProviderUserList:
        """Read a public AniList anime list. No auth: MediaListCollection is readable
        anonymously for public profiles, and this integration is read-only and one-way,
        permanently.

        Each chunk request goes through the shared ProviderHTTPClient and this provider's own
        RateLimiter, so the loop is throttled by machinery that already exists.
        """
        entries: list[ProviderListEntry] = []
        seen: set[str] = set()
        dropped = 0
        truncated = False

        for chunk in range(1, MAX_LIST_CHUNKS + 1):
            collection = await self._fetch_list_chunk(username, chunk)
            chunk_entries, chunk_dropped = mapper.to_list_entries(collection, seen)
            entries.extend(chunk_entries)
            dropped += chunk_dropped
            if not collection.get("hasNextChunk"):
                break
        else:
            # Decision 4-L: reported to the caller, not only to the log.
            truncated = True
            logger.warning(
                "AniList list for %r exceeded %d chunks; importing the first %d entries only",
                username,
                MAX_LIST_CHUNKS,
                len(entries),
            )

        return ProviderUserList(entries=tuple(entries), dropped=dropped, truncated=truncated)

    async def _fetch_list_chunk(self, username: str, chunk: int) -> dict[str, Any]:
        try:
            body = await self._post(USER_LIST_QUERY, {"name": username, "chunk": chunk, "perChunk": PER_CHUNK})
        except AniListGraphQLError as exc:
            if exc.mentions_missing_user():
                raise UserListNotAvailable(f"no public AniList list for {username!r}") from exc
            raise

        if body is None:
            # _post returns None for a 404, which is how AniList answers an unknown username —
            # verified against the live API.
            raise UserListNotAvailable(f"no public AniList list for {username!r}")

        collection = body["data"].get("MediaListCollection")
        if not isinstance(collection, dict):
            if chunk > 1:
                # Only chunk 1 can mean "no such list". Past the end, a null collection is the
                # ordinary "you asked for more than exists" answer; 404-ing here would discard
                # chunk 1's entries and report a readable profile as missing.
                return {"hasNextChunk": False, "lists": []}
            raise UserListNotAvailable(f"no public AniList list for {username!r}")
        return collection

    async def _post(self, query: str, variables: dict[str, Any]) -> dict[str, Any] | None:
        """None means "404" — callers decide what that means: get_by_id treats it as "no such
        record", search() cannot (a search endpoint has no record to be missing) and raises.
        Anything else returned here is a JSON object carrying no `errors` entry and a `data`
        object — checked here rather than assumed, so both callers can index `body["data"]`
        instead of each inventing its own reading of the same untrusted body.
        """
        response = await self._client.request("POST", ANILIST_URL, json={"query": query, "variables": variables})
        # 404 is checked BEFORE the errors array, not after: AniList's 404 body also carries an
        # `errors` entry, so the other order would raise where it should return None.
        if response.status_code == 404:
            return None
        try:
            body: Any = response.json()
        except ValueError as exc:
            # The shared transport already turns every non-2xx, non-404 response into
            # ProviderUnavailable, so what reaches here is a 2xx carrying a body we cannot read:
            # a proxy interstitial, a truncated payload, a mis-encoded one. `ValueError`, not
            # `json.JSONDecodeError`: httpx calls json.loads on raw *bytes*, so an invalid-UTF-8
            # body raises UnicodeDecodeError — also a ValueError, but not a JSONDecodeError.
            # Either one left unwrapped escapes Task 3.7's `except ProviderError` as a 500.
            raise ProviderUnavailable(f"AniList returned {response.status_code} with an unreadable body") from exc
        if not isinstance(body, dict):
            # An annotation is not a check. A 200 whose body is a JSON array parses fine and then
            # raises AttributeError on the first `.get()` — untyped, out of a method whose
            # contract is that it raises ProviderError subclasses.
            raise ProviderUnavailable("AniList returned a JSON body that is not an object")
        if body.get("errors"):
            raise AniListGraphQLError(f"AniList returned errors: {body['errors']}", body["errors"])
        if not isinstance(body.get("data"), dict):
            raise ProviderUnavailable("AniList returned a body with no data object")
        return body
