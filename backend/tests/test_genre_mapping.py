import logging

import pytest

from app.media.providers.genres import ANILIST_GENRES, CANONICAL_GENRES, TMDB_GENRES, map_genres
from tests.fixtures.loader import load_fixture


def test_anilist_table_matches_the_published_list_exactly():
    published = set(load_fixture("anilist", "genre_collection")["data"]["GenreCollection"])
    assert set(ANILIST_GENRES) == published


def test_tmdb_table_matches_the_published_list_exactly():
    published = {entry["id"] for entry in load_fixture("tmdb", "genre_tv_list")["genres"]}
    assert set(TMDB_GENRES) == published


@pytest.mark.parametrize("table", [ANILIST_GENRES, TMDB_GENRES], ids=["anilist", "tmdb"])
def test_every_mapped_value_is_canonical(table):
    for key, mapped in table.items():
        assert mapped <= CANONICAL_GENRES, f"{key!r} maps outside the canonical vocabulary"


def test_every_canonical_genre_is_reachable_from_some_provider():
    """A canonical genre no provider produces is dead weight in Phase 7's scoring."""
    reachable = set().union(*ANILIST_GENRES.values(), *TMDB_GENRES.values())
    assert reachable == CANONICAL_GENRES


def test_a_compound_genre_fans_out_to_two_canonical_names():
    assert map_genres(TMDB_GENRES, [10759]) == ("action", "adventure")


def test_output_is_sorted_and_deduplicated():
    """Two upstream genres overlapping on one canonical name must not double it, and the order
    must not depend on the upstream's order — the value lands in a Postgres ARRAY that Phase 7
    compares.
    """
    assert map_genres(TMDB_GENRES, [10765, 10759]) == ("action", "adventure", "fantasy", "sci_fi")


def test_unmapped_values_are_dropped_and_logged(caplog):
    with caplog.at_level(logging.WARNING):
        assert map_genres(TMDB_GENRES, [10759, 999999]) == ("action", "adventure")
    assert "999999" in caplog.text


def test_deliberately_unmapped_genres_produce_nothing():
    """Animation is a medium, not a genre — MediaType already carries it. Letting it through
    would overlap-match every anime against every other and flatten Phase 7's scoring.
    """
    assert map_genres(TMDB_GENRES, [16, 10763]) == ()
