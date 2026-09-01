import uuid
from decimal import Decimal

from app.recommendations.scoring import (
    Candidate,
    Edge,
    TasteEntry,
    genre_affinity,
    rank_candidates,
    signal_weight,
)


def _id():
    return uuid.uuid4()


# rank_candidates sorts on (-score, media_id.bytes), so the LOWER id wins an exact tie. Tests that
# would be decided by that tiebreak if the formula regressed give the WRONG candidate the winner,
# which turns a coin flip into a deterministic failure.
_TIEBREAK_WINNER = uuid.UUID("00000000-0000-4000-8000-000000000001")
_TIEBREAK_LOSER = uuid.UUID("ffffffff-ffff-4fff-bfff-ffffffffffff")


def entry(*, genres=("action",), score=None, favorite=False, completed=False, media_id=None):
    return TasteEntry(
        media_id=media_id or _id(),
        genres=genres,
        score=Decimal(score) if score is not None else None,
        favorite=favorite,
        completed=completed,
    )


def test_signal_weight_scales_a_score_across_its_useful_range():
    assert signal_weight(entry(score="7")) == 0.25
    assert signal_weight(entry(score="10")) == 1.0


def test_a_score_below_the_threshold_is_no_signal_at_all():
    # Not a NEGATIVE signal — decision 7-H rejects those for the MVP. Simply absent.
    assert signal_weight(entry(score="6.9")) == 0.0


def test_favorite_outranks_a_bare_seven_and_wins_when_both_apply():
    assert signal_weight(entry(favorite=True)) == 1.0
    assert signal_weight(entry(score="7", favorite=True)) == 1.0


def test_finishing_a_show_is_a_weak_positive_below_an_explicit_seven():
    weight = signal_weight(entry(completed=True))
    assert 0 < weight < signal_weight(entry(score="7"))


def test_idf_suppresses_a_genre_that_is_on_everything():
    """The whole point of the IDF term: "you like drama" is true of nearly everyone."""
    entries = [entry(genres=("drama", "mecha"), score="10")]
    affinity = genre_affinity(entries, {"drama": 5000, "mecha": 12})

    assert affinity["mecha"] > affinity["drama"]


def test_an_empty_profile_produces_no_affinity_rather_than_dividing_by_zero():
    assert genre_affinity([entry(score="3")], {"action": 10}) == {}


def test_genre_stuffing_does_not_win_on_surface_area():
    """sqrt length normalisation: a title tagged with everything overlaps everything.

    The ids are PINNED, not uuid4, and their order is the assertion's teeth. Only `mecha` is in
    the profile, so both candidates match the same single genre — delete the sqrt divisor in
    _genre_match and the two scores become exactly EQUAL, decided by the media_id tiebreak. With
    random ids that regression would surface about half the time. `stuffed` sorts first on a tie
    (ties break on media_id.bytes ascending), so the wrong answer loses the tiebreak and this test
    fails every time rather than sometimes.
    """
    entries = [entry(genres=("mecha",), score="10")]
    counts = {"mecha": 10, "comedy": 10, "drama": 10, "horror": 10, "romance": 10}
    seed = entries[0].media_id
    focused = Candidate(media_id=_TIEBREAK_LOSER, genres=("mecha",), edges=(Edge(seed, 0),))
    stuffed = Candidate(
        media_id=_TIEBREAK_WINNER,
        genres=("mecha", "comedy", "drama", "horror", "romance"),
        edges=(Edge(seed, 0),),
    )

    ranked = rank_candidates([stuffed, focused], entries, counts)

    assert ranked[0].media_id == focused.media_id


def test_a_candidate_with_no_genre_overlap_still_survives():
    """The alpha floor. Genre is coarse and blind to tone; provider signal alone can carry a
    title. This is exactly why there is no `&&` prune on membership (decision 7-J)."""
    entries = [entry(genres=("mecha",), score="10")]
    seed = entries[0].media_id
    unrelated = Candidate(media_id=_id(), genres=("western",), edges=(Edge(seed, 0),))

    ranked = rank_candidates([unrelated], entries, {"mecha": 10, "western": 10})

    assert len(ranked) == 1
    assert ranked[0].score > 0


def test_corroboration_from_several_seeds_beats_a_single_one():
    """provider_signal SUMS over every seed pointing at the candidate.

    Pinned ids for the reason the stuffing test above spells out: swap the sum for a max and both
    candidates score exactly the same, so only the tiebreak separates them. `single` is given the
    lower id, so it wins that tiebreak and the wrong answer fails deterministically.
    """
    a = entry(genres=("mecha",), score="10")
    b = entry(genres=("mecha",), score="10")
    single = Candidate(media_id=_TIEBREAK_WINNER, genres=("mecha",), edges=(Edge(a.media_id, 0),))
    both = Candidate(
        media_id=_TIEBREAK_LOSER,
        genres=("mecha",),
        edges=(Edge(a.media_id, 0), Edge(b.media_id, 0)),
    )

    ranked = rank_candidates([single, both], [a, b], {"mecha": 10})

    assert ranked[0].media_id == both.media_id


def test_the_reason_names_the_strongest_single_seed_not_the_first_one():
    weak, strong = entry(genres=("mecha",), score="7"), entry(genres=("mecha",), score="10")
    candidate = Candidate(
        media_id=_id(),
        genres=("mecha",),
        edges=(Edge(weak.media_id, 0), Edge(strong.media_id, 0)),
    )

    ranked = rank_candidates([candidate], [weak, strong], {"mecha": 10})

    assert ranked[0].seed_media_id == strong.media_id


def test_ranking_is_deterministic_when_scores_tie():
    """Ranks must not shuffle between recomputes: the cursor points into this ordering."""
    seed_entry = entry(genres=("mecha",), score="10")
    left = Candidate(media_id=_id(), genres=("mecha",), edges=(Edge(seed_entry.media_id, 0),))
    right = Candidate(media_id=_id(), genres=("mecha",), edges=(Edge(seed_entry.media_id, 0),))

    first = rank_candidates([left, right], [seed_entry], {"mecha": 10})
    second = rank_candidates([right, left], [seed_entry], {"mecha": 10})

    assert [s.media_id for s in first] == [s.media_id for s in second]
