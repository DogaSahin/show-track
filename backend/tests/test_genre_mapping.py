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


def test_only_the_deliberately_excluded_genres_map_to_nothing():
    """An empty mapping is indistinguishable from a correct one under the subset and reachability
    checks above, so the exclusions have to be pinned by name. Animation (16) and News (10763) are
    dropped on purpose — Animation is a medium, not a genre, and MediaType already carries it.
    Anything else emptying out is a bug that would otherwise ship green.
    """
    assert {key for key, value in TMDB_GENRES.items() if not value} == {16, 10763}
    assert {key for key, value in ANILIST_GENRES.items() if not value} == {"Hentai"}


# Compound names fan out to two canonical genres, and two ids map to nothing on purpose, so
# neither group follows the slug rule. Listing them here is what keeps the rule honest for the
# other eleven rather than weakening it to fit the exceptions.
_TMDB_NOT_SLUG_DERIVABLE = {
    10759,  # Action & Adventure -> two genres
    10765,  # Sci-Fi & Fantasy   -> two genres
    10768,  # War & Politics     -> war only; "politics" has no canonical genre
    16,  # Animation          -> deliberately empty
    10763,  # News               -> deliberately empty
}


def test_single_word_tmdb_genres_map_to_their_own_name():
    """Turns the id-to-name comments in genres.py into a verified claim.

    The expectation comes from the committed fixture, not from the table, so a transposed entry
    (an id commented one thing and mapped to another) fails here. Note this depends on the fixture
    carrying ENGLISH names: TMDB localizes genre names per request, which is exactly why the table
    is keyed by integer id. If someone re-records the fixture from a non-English response, this
    test failing is correct — the fixture is what needs fixing.
    """
    for entry in load_fixture("tmdb", "genre_tv_list")["genres"]:
        if entry["id"] in _TMDB_NOT_SLUG_DERIVABLE:
            continue
        slug = entry["name"].lower().replace("-", "_").replace(" ", "_")
        assert slug in TMDB_GENRES[entry["id"]], f"{entry['name']} ({entry['id']}) does not map to {slug!r}"
