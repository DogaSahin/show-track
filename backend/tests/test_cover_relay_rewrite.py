"""Outgoing TMDB poster URLs point at the relay path; stored rows and other sources are untouched."""

import uuid

import pytest
from sqlalchemy import select

from app.groups.models import GroupRole
from app.media.models import Media, MediaSource, MediaType
from app.media.providers.base import MediaRef, ProviderMediaSummary, ProviderSearchPage
from app.media.providers.images import relay_cover_url
from tests.factories import make_group, make_group_member, make_media, make_user_media, make_watchlist_entry
from tests.test_media_search_routes import FakeProvider

TMDB_COVER = "https://image.tmdb.org/t/p/w500/abc123.jpg"
ANILIST_COVER = "https://s4.anilist.co/file/x.jpg"
RELAY = "/v1/images/tmdb/w500/abc123.jpg"


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        (TMDB_COVER, RELAY),
        ("https://image.tmdb.org/t/p/original/abc.jpg", "https://image.tmdb.org/t/p/original/abc.jpg"),
        ("https://image.tmdb.org/t/p/w500/a-b.jpg", "https://image.tmdb.org/t/p/w500/a-b.jpg"),
        (ANILIST_COVER, ANILIST_COVER),
        ("http://image.tmdb.org/t/p/w500/abc.jpg", "http://image.tmdb.org/t/p/w500/abc.jpg"),
        (None, None),
    ],
)
def test_relay_cover_url(url, expected):
    assert relay_cover_url(url) == expected


async def _seed(db_session, auth_user):
    """A TMDB-cover and an AniList-cover title, both in the caller's library and a group's
    watchlist (the caller is a member)."""
    tmdb = make_media(source=MediaSource.TMDB, type=MediaType.TV, external_id="1", cover_image_url=TMDB_COVER)
    anilist = make_media(external_id="2", cover_image_url=ANILIST_COVER)
    db_session.add_all([tmdb, anilist])
    await db_session.flush()
    group = make_group(created_by=auth_user.id, invite_code=uuid.uuid4().hex[:12].upper())
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, auth_user.id, role=GroupRole.OWNER))
    db_session.add_all([make_user_media(auth_user.id, m.id) for m in (tmdb, anilist)])
    db_session.add_all([make_watchlist_entry(group.id, m.id) for m in (tmdb, anilist)])
    await db_session.flush()
    return tmdb, anilist, group


async def test_media_detail_rewrites_tmdb_only(auth_client, auth_user, db_session):
    tmdb, anilist, _ = await _seed(db_session, auth_user)

    assert (await auth_client.get(f"/v1/media/{tmdb.id}")).json()["cover_image_url"] == RELAY
    assert (await auth_client.get(f"/v1/media/{anilist.id}")).json()["cover_image_url"] == ANILIST_COVER


async def test_library_rewrites_tmdb_only(auth_client, auth_user, db_session):
    await _seed(db_session, auth_user)

    items = (await auth_client.get("/v1/library")).json()["items"]

    assert {i["media"]["cover_image_url"] for i in items} == {RELAY, ANILIST_COVER}


async def test_group_watchlist_preview_rewrites_tmdb_only(auth_client, auth_user, db_session):
    await _seed(db_session, auth_user)

    summary = (await auth_client.get("/v1/groups")).json()[0]

    assert {w["cover_image_url"] for w in summary["watchlist_preview"]} == {RELAY, ANILIST_COVER}


async def test_search_rewrites_tmdb_cover(auth_client, use_providers):
    class CoverProvider(FakeProvider):
        async def search(self, query, page):
            return ProviderSearchPage(
                items=(
                    ProviderMediaSummary(
                        ref=MediaRef(source=MediaSource.TMDB, external_id="9"),
                        type=MediaType.TV,
                        title="t",
                        year=2020,
                        genres=(),
                        cover_image_url=TMDB_COVER,
                    ),
                ),
                has_more=False,
            )

    use_providers({MediaSource.TMDB: CoverProvider(MediaSource.TMDB, MediaType.TV, [])})

    body = (await auth_client.get("/v1/media/search", params={"q": "x"})).json()

    assert body["items"][0]["cover_image_url"] == RELAY


async def test_stored_cover_is_not_rewritten(auth_client, auth_user, db_session):
    tmdb, _, _ = await _seed(db_session, auth_user)

    await auth_client.get(f"/v1/media/{tmdb.id}")

    stored = await db_session.scalar(select(Media.cover_image_url).where(Media.id == tmdb.id))
    assert stored == TMDB_COVER
