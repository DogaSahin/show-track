from typing import Any

import pytest

from app.media.models import MediaSource, MediaType
from app.media.providers.base import (
    MediaProvider,
    MediaRef,
    ProviderMedia,
    ProviderMediaSummary,
    ProviderSearchPage,
)
from app.media.providers.errors import ProviderRateLimited, ProviderTimeout, ProviderUnavailable


class FakeProvider(MediaProvider):
    """A provider that answers from a canned page, or raises, without any transport."""

    def __init__(
        self,
        source: MediaSource,
        media_type: MediaType,
        titles: list[str],
        *,
        has_more: bool = False,
        error: Exception | None = None,
    ) -> None:
        self.source = source
        self.media_type = media_type
        self._titles = titles
        self._has_more = has_more
        self._error = error

    async def search(self, query: str, page: int) -> ProviderSearchPage:
        if self._error is not None:
            raise self._error
        return ProviderSearchPage(
            items=tuple(
                ProviderMediaSummary(
                    ref=MediaRef(source=self.source, external_id=str(index)),
                    type=self.media_type,
                    title=title,
                    year=2020,
                    genres=("action",),
                    cover_image_url=None,
                )
                for index, title in enumerate(self._titles)
            ),
            has_more=self._has_more,
        )

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        return None


def anilist(titles: list[str], **kwargs: Any) -> FakeProvider:
    return FakeProvider(MediaSource.ANILIST, MediaType.ANIME, titles, **kwargs)


def tmdb(titles: list[str], **kwargs: Any) -> FakeProvider:
    return FakeProvider(MediaSource.TMDB, MediaType.TV, titles, **kwargs)


async def test_results_are_interleaved_not_concatenated(auth_client, use_providers):
    use_providers(
        {
            MediaSource.ANILIST: anilist(["a1", "a2", "a3"]),
            MediaSource.TMDB: tmdb(["t1", "t2"]),
        }
    )

    response = await auth_client.get("/v1/media/search", params={"q": "x"})

    assert response.status_code == 200
    assert [item["title"] for item in response.json()["items"]] == ["a1", "t1", "a2", "t2", "a3"]


async def test_one_provider_failing_still_returns_the_others_results(auth_client, use_providers):
    use_providers(
        {
            MediaSource.ANILIST: anilist(["a1"]),
            MediaSource.TMDB: tmdb([], error=ProviderUnavailable("503")),
        }
    )

    body = (await auth_client.get("/v1/media/search", params={"q": "x"})).json()

    assert [item["title"] for item in body["items"]] == ["a1"]
    assert body["sources"] == {"anilist": "ok", "tmdb": "error"}


async def test_an_unexpected_exception_is_caught_not_propagated(auth_client, use_providers):
    """Regression for the CRITICAL finding: _search_one must never let a non-ProviderError
    escape into asyncio.gather, or one provider's bug 500s the whole request and discards the
    healthy sibling's page. KeyError stands in for the real shapes that trigger this (a mapper
    indexing a missing "id", a 200 body that is a bare JSON array reaching `.get()`).
    """
    use_providers(
        {
            MediaSource.ANILIST: anilist(["a1"]),
            MediaSource.TMDB: tmdb([], error=KeyError("id")),
        }
    )

    response = await auth_client.get("/v1/media/search", params={"q": "x"})
    body = response.json()

    assert response.status_code == 200
    assert [item["title"] for item in body["items"]] == ["a1"]
    assert body["sources"] == {"anilist": "ok", "tmdb": "error"}


@pytest.mark.parametrize(
    ("error", "expected"),
    [(ProviderTimeout("slow"), "timeout"), (ProviderRateLimited(retry_after=5), "rate_limited")],
)
async def test_failure_kind_is_reported_per_source(auth_client, use_providers, error, expected):
    use_providers({MediaSource.ANILIST: anilist([], error=error)})
    body = (await auth_client.get("/v1/media/search", params={"q": "x"})).json()
    assert body["sources"]["anilist"] == expected


async def test_an_unregistered_provider_reports_not_configured(auth_client, use_providers):
    """This is how a client distinguishes "TMDB found nothing" from "this server has no TMDB key"."""
    use_providers({MediaSource.ANILIST: anilist(["a1"])})

    body = (await auth_client.get("/v1/media/search", params={"q": "x"})).json()

    assert body["sources"] == {"anilist": "ok", "tmdb": "not_configured"}


async def test_has_more_is_true_when_either_provider_has_more(auth_client, use_providers):
    use_providers(
        {
            MediaSource.ANILIST: anilist(["a1"], has_more=False),
            MediaSource.TMDB: tmdb(["t1"], has_more=True),
        }
    )
    assert (await auth_client.get("/v1/media/search", params={"q": "x"})).json()["has_more"] is True


async def test_search_requires_authentication(client, use_providers):
    use_providers({MediaSource.ANILIST: anilist(["a1"])})
    assert (await client.get("/v1/media/search", params={"q": "x"})).status_code == 401


async def test_a_missing_query_is_a_422(auth_client, use_providers):
    use_providers({MediaSource.ANILIST: anilist(["a1"])})
    assert (await auth_client.get("/v1/media/search")).status_code == 422
