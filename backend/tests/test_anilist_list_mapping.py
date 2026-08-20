import re
from decimal import Decimal

import pytest

from app.media.providers.anilist import mapper
from app.media.providers.anilist.queries import MEDIA_QUERY, SEARCH_QUERY, USER_LIST_QUERY
from app.media.providers.base import ListEntryStatus
from tests.fixtures.loader import load_fixture


def _collection() -> dict:
    return load_fixture("anilist", "user_list")["data"]["MediaListCollection"]


def _entry(status: str, **overrides) -> dict:
    base = {
        "status": status,
        "score": 7.0,
        "progress": 3,
        "media": {"id": 1, "title": {"romaji": "X"}, "genres": [], "status": "FINISHED"},
    }
    return {**base, **overrides}


def _collection_of(*entries: dict) -> dict:
    return {"hasNextChunk": False, "lists": [{"name": "L", "entries": list(entries)}]}


@pytest.mark.parametrize(
    ("anilist_status", "expected"),
    [
        ("CURRENT", ListEntryStatus.WATCHING),
        ("REPEATING", ListEntryStatus.WATCHING),
        ("PLANNING", ListEntryStatus.PLANNED),
        ("COMPLETED", ListEntryStatus.COMPLETED),
        ("DROPPED", ListEntryStatus.DROPPED),
        ("PAUSED", ListEntryStatus.PAUSED),
    ],
)
def test_every_anilist_status_maps(anilist_status, expected):
    """REPEATING collapses into WATCHING: a rewatch is still watching. PAUSED stays distinct from
    DROPPED because collapsing them destroys information no re-import can recover.
    """
    entries, dropped = mapper.to_list_entries(_collection_of(_entry(anilist_status)), set())

    assert dropped == 0
    assert entries[0].status is expected


def test_an_unknown_status_is_dropped_not_defaulted():
    """Decision 4-I. A dropped entry shows up in the summary's `failed` count; a mislabelled one
    is silent and permanent. When a mapping is uncertain, prefer the wrongness you can see.
    """
    entries, dropped = mapper.to_list_entries(_collection_of(_entry("SOMETHING_NEW")), set())

    assert entries == ()
    assert dropped == 1


def test_score_zero_is_unscored_not_a_rating():
    """In AniList 0 means unscored. Stored as 0.0 it drags the average-score stat down and tells
    the recommender you hated everything you never rated.
    """
    entries, _ = mapper.to_list_entries(_collection_of(_entry("CURRENT", score=0)), set())

    assert entries[0].score is None


def test_a_real_score_round_trips_at_one_decimal():
    entries, _ = mapper.to_list_entries(_collection_of(_entry("CURRENT", score=8.5)), set())

    assert entries[0].score == Decimal("8.5")


@pytest.mark.parametrize("score", [85, 1e400, float("nan"), -3], ids=["point-100", "overflow", "nan", "negative"])
def test_an_out_of_range_score_is_treated_as_unscored(score):
    """85 on a 1-10 column means the score format assumption is wrong -- POINT_100 read as
    POINT_10_DECIMAL. Clipping it to 10.0 would write a plausible wrong number and hide the
    misconfiguration. `1e400` is `inf` after json.loads and would raise InvalidOperation out of
    quantize if the range check ran second, aborting the whole atomic import as a 500.
    """
    entries, _ = mapper.to_list_entries(_collection_of(_entry("CURRENT", score=score)), set())

    assert entries[0].score is None


@pytest.mark.parametrize("progress", [2**31, -1, "12", None], ids=["overflows-int4", "negative", "string", "null"])
def test_an_unusable_progress_falls_back_to_zero(progress):
    """user_media.progress is int4. One out-of-range value would abort the entire single
    transaction import as a 500 rather than landing in `failed`.
    """
    entries, _ = mapper.to_list_entries(_collection_of(_entry("CURRENT", progress=progress)), set())

    assert entries[0].progress == 0


def test_a_title_in_two_lists_is_imported_once():
    """AniList groups entries into `lists`, and with custom lists enabled (MediaListGroup carries
    isCustomList -- confirmed by introspection) a title appears in its status list AND every
    custom list it belongs to. Deduplication is not tidiness: a duplicated conflict key inside
    one INSERT ... ON CONFLICT DO UPDATE raises cardinality_violation.
    """
    entries, dropped = mapper.to_list_entries(_collection(), set())

    assert dropped == 0
    assert [entry.media.ref.external_id for entry in entries] == ["154587", "16498"]


def test_dedupe_carries_across_chunks():
    """`seen` is threaded through by the caller so a title split across two chunk responses is
    still imported once.
    """
    seen: set[str] = set()
    mapper.to_list_entries(_collection(), seen)

    entries, _ = mapper.to_list_entries(_collection(), seen)

    assert entries == ()


def test_a_duplicated_unmappable_entry_is_counted_once():
    """`seen` is marked before the status branch, so a title AniList returns twice with an
    unknown status adds 1 to `dropped`, not 2 -- the count decision 4-I exists to make
    trustworthy.
    """
    bad = _entry("SOMETHING_NEW")
    collection = {"hasNextChunk": False, "lists": [{"entries": [bad]}, {"entries": [bad]}]}

    entries, dropped = mapper.to_list_entries(collection, set())

    assert entries == ()
    assert dropped == 1


def test_the_fixture_maps_media_through_the_shared_mapper():
    entries, _ = mapper.to_list_entries(_collection(), set())
    frieren = entries[0]

    assert frieren.media.title == "Frieren: Beyond Journey's End"
    assert frieren.media.year == 2023
    assert frieren.media.genres == ("adventure", "drama", "fantasy")
    assert frieren.progress == 12


@pytest.mark.parametrize("query", [SEARCH_QUERY, MEDIA_QUERY, USER_LIST_QUERY], ids=["search", "media", "user-list"])
def test_every_query_defines_the_fragments_it_spreads(query):
    """A query spreading an undefined fragment is a GraphQL error at runtime and nothing catches
    it earlier. This is the guard that lets the three queries share field lists structurally
    instead of by three hand-written copies agreeing by luck.
    """
    spread = set(re.findall(r"\.\.\.(\w+)", query))
    defined = set(re.findall(r"fragment (\w+) on ", query))

    assert spread <= defined, f"spreads {spread - defined} with no fragment definition"
