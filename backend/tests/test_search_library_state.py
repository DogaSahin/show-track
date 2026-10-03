"""GET /v1/media/search marks stored and tracked results (BT-04)."""

from contextlib import contextmanager
from typing import Any

from sqlalchemy import event

from app.library.models import UserMediaStatus
from app.media.models import MediaSource, MediaType
from app.media.providers.base import MediaProvider, MediaRef, ProviderMediaSummary, ProviderSearchPage
from app.media.providers.errors import ProviderTimeout
from tests.factories import make_media, make_user, make_user_media


class PageProvider(MediaProvider):
    """Answers a search with titles whose external ids are "0", "1", …, or raises."""

    def __init__(
        self, source: MediaSource, media_type: MediaType, count: int, *, error: Exception | None = None
    ) -> None:
        self.source = source
        self.media_type = media_type
        self._count = count
        self._error = error

    async def search(self, query: str, page: int) -> ProviderSearchPage:
        if self._error is not None:
            raise self._error
        return ProviderSearchPage(
            items=tuple(
                ProviderMediaSummary(
                    ref=MediaRef(source=self.source, external_id=str(index)),
                    type=self.media_type,
                    title=f"{self.source.value} {index}",
                    year=2020,
                    genres=("action",),
                    cover_image_url=None,
                )
                for index in range(self._count)
            ),
            has_more=False,
        )

    async def get_by_id(self, external_id: str) -> Any:
        return None

    async def fetch_similar(self, external_id: str):
        raise AssertionError("not used")


@contextmanager
def _counting_queries(db_session):
    statements: list[str] = []

    def count(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    engine = db_session.bind.engine.sync_engine
    event.listen(engine, "before_cursor_execute", count)
    try:
        yield statements
    finally:
        event.remove(engine, "before_cursor_execute", count)


async def _store(db_session, external_id: str):
    media = make_media(source=MediaSource.ANILIST, external_id=external_id, title=f"anilist {external_id}")
    db_session.add(media)
    await db_session.flush()
    return media


def _by_id(body: dict) -> dict[str, dict]:
    return {item["external_id"]: item for item in body["items"] if item["source"] == "anilist"}


async def test_results_are_marked_unstored_stored_or_tracked(auth_client, auth_user, db_session, use_providers):
    stored = await _store(db_session, "1")
    tracked = await _store(db_session, "2")
    entry = make_user_media(auth_user.id, tracked.id, status=UserMediaStatus.WATCHING)
    db_session.add(entry)
    await db_session.flush()
    use_providers({MediaSource.ANILIST: PageProvider(MediaSource.ANILIST, MediaType.ANIME, 3)})

    items = _by_id((await auth_client.get("/v1/media/search", params={"q": "x"})).json())

    assert items["0"]["media_id"] is None and items["0"]["library_entry"] is None
    assert items["1"]["media_id"] == str(stored.id) and items["1"]["library_entry"] is None
    assert items["2"]["media_id"] == str(tracked.id)
    assert items["2"]["library_entry"] == {"id": str(entry.id), "status": "watching"}


async def test_another_users_entry_never_marks_a_result(auth_client, db_session, use_providers):
    other = make_user(username="search-other", email="search-other@example.com")
    db_session.add(other)
    await db_session.flush()
    theirs = await _store(db_session, "0")
    db_session.add(make_user_media(other.id, theirs.id))
    await db_session.flush()
    use_providers({MediaSource.ANILIST: PageProvider(MediaSource.ANILIST, MediaType.ANIME, 1)})

    items = _by_id((await auth_client.get("/v1/media/search", params={"q": "x"})).json())

    assert items["0"]["media_id"] == str(theirs.id)
    assert items["0"]["library_entry"] is None


async def test_one_query_marks_the_page_whatever_its_size(auth_client, db_session, use_providers):
    await _store(db_session, "0")
    use_providers({MediaSource.ANILIST: PageProvider(MediaSource.ANILIST, MediaType.ANIME, 2)})
    with _counting_queries(db_session) as small:
        await auth_client.get("/v1/media/search", params={"q": "x"})

    use_providers({MediaSource.ANILIST: PageProvider(MediaSource.ANILIST, MediaType.ANIME, 20)})
    with _counting_queries(db_session) as large:
        await auth_client.get("/v1/media/search", params={"q": "x"})

    assert small, "the listener saw no statements, so the comparison below would prove nothing"
    assert len(small) == len(large)


async def test_a_degraded_search_still_marks_what_came_back(auth_client, db_session, use_providers):
    stored = await _store(db_session, "0")
    use_providers(
        {
            MediaSource.ANILIST: PageProvider(MediaSource.ANILIST, MediaType.ANIME, 1),
            MediaSource.TMDB: PageProvider(MediaSource.TMDB, MediaType.TV, 0, error=ProviderTimeout("slow")),
        }
    )

    body = (await auth_client.get("/v1/media/search", params={"q": "x"})).json()

    assert body["sources"]["tmdb"] != "ok"
    assert _by_id(body)["0"]["media_id"] == str(stored.id)
