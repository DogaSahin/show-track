"""Recommendation scoring. Pure functions — no session, no I/O, no clock.

Kept separate from service.py so the formula is testable against plain fixtures with no database.
That matters more here than usual: every constant below is REASONED, NOT MEASURED. A single-user
tracker has no ground truth and nothing to A/B against, so these will be revisited, and the cost of
revisiting them should be one diff and one fast test file.
"""

import math
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal

# --- The knobs. All of them. Do not scatter these. (decisions 7-H, 7-I) -------------------------

# Below this, a score is not a signal. It is NOT a negative signal: 7-H rejected negative weights
# for the MVP because one dropped show can bury a genre you otherwise like.
SCORE_THRESHOLD = Decimal("7")
# score 7 -> 0.25, score 10 -> 1.0. The NUMERIC(3,1) column exists so this has real resolution.
SCORE_FLOOR, SCORE_SPAN = Decimal("6"), Decimal("4")
# An explicit thumbs-up outranks anything inferred.
FAVORITE_WEIGHT = 1.0
# Finishing a show is a revealed preference, deliberately weaker than an explicit 7 (0.25).
COMPLETED_WEIGHT = 0.15
# Reciprocal-rank decay over the provider's ordering: 1st -> 1.0, 2nd -> 0.5, 3rd -> 0.33.
RR_NUMERATOR = 1.0
# How much of a candidate's score survives with zero genre overlap. Genre is coarse and blind to
# tone, pacing and writing, so provider signal alone must be able to carry a title; genre boosts it
# by up to 1/ALPHA. This floor is precisely why there is no genre predicate on membership (7-J).
ALPHA = 0.4


@dataclass(frozen=True, slots=True)
class TasteEntry:
    """One library entry, reduced to what scoring needs."""

    media_id: uuid.UUID
    genres: tuple[str, ...]
    score: Decimal | None
    favorite: bool
    completed: bool


@dataclass(frozen=True, slots=True)
class Edge:
    seed_media_id: uuid.UUID
    position: int


@dataclass(frozen=True, slots=True)
class Candidate:
    media_id: uuid.UUID
    genres: tuple[str, ...]
    edges: tuple[Edge, ...]


@dataclass(frozen=True, slots=True)
class Scored:
    media_id: uuid.UUID
    score: float
    seed_media_id: uuid.UUID
    matched_genres: tuple[str, ...]


def signal_weight(entry: TasteEntry) -> float:
    """How much this entry says about your taste. Strongest applicable signal wins (7-H).

    Returns 0.0 for an entry that says nothing — an unscored `planned` title, or one scored below
    the threshold. Zero-weight entries contribute to neither side of the TF ratio.
    """
    weights: list[float] = []
    if entry.score is not None and entry.score >= SCORE_THRESHOLD:
        weights.append(float((entry.score - SCORE_FLOOR) / SCORE_SPAN))
    if entry.favorite:
        weights.append(FAVORITE_WEIGHT)
    if entry.completed:
        weights.append(COMPLETED_WEIGHT)
    return max(weights, default=0.0)


def genre_affinity(entries: Sequence[TasteEntry], genre_counts: Mapping[str, int]) -> dict[str, float]:
    """Your taste as a weight per canonical genre. TF-IDF.

    TF is the share of your positive signal that lands on a genre. IDF divides that by how common
    the genre is across all known titles — which is what separates "you like drama", true of nearly
    everyone and therefore worthless, from "you like mecha", which discriminates.

    `genre_counts` is clamped at 1: log(1 + 0) is 0 and would divide by zero, and a genre that is
    in your profile necessarily appears on at least one title anyway.
    """
    weighted = [(entry, signal_weight(entry)) for entry in entries]
    total = sum(weight for _, weight in weighted)
    if total == 0:
        return {}

    raw: dict[str, float] = {}
    for entry, weight in weighted:
        if weight == 0:
            continue
        for genre in entry.genres:
            raw[genre] = raw.get(genre, 0.0) + weight

    return {genre: (weight / total) / math.log(1 + max(1, genre_counts.get(genre, 1))) for genre, weight in raw.items()}


def _genre_match(genres: tuple[str, ...], affinity: Mapping[str, float]) -> tuple[float, tuple[str, ...]]:
    """Length-normalised overlap, plus the genres that matched.

    Dividing by sqrt(len(genres)) is cosine similarity's instinct: a title tagged with eight
    genres would otherwise beat a focused one on surface area alone. It slightly penalises
    genuinely broad shows, which is the accepted trade.
    """
    if not genres:
        return 0.0, ()
    matched = tuple(sorted(genre for genre in genres if genre in affinity))
    return sum(affinity[genre] for genre in matched) / math.sqrt(len(genres)), matched


def rank_candidates(
    candidates: Sequence[Candidate],
    entries: Sequence[TasteEntry],
    genre_counts: Mapping[str, int],
) -> list[Scored]:
    """The blend, ordered. The INDEX of each result is its rank.

    final = provider_signal x (ALPHA + (1 - ALPHA) x genre_match)

    provider_signal SUMS over every seed pointing at the candidate, because three titles you loved
    all agreeing is stronger evidence than one. The reason, though, takes the MAX single
    contributor — summing for rank and maxing for explanation is a deliberate inconsistency, since
    "because you liked these four things a little" is not an explanation anyone can act on.

    Ties break on media_id so the ordering is stable across recomputes. That is not cosmetic: the
    cursor points into this ordering, and a shuffle would skip or repeat rows on the next page.
    """
    affinity = genre_affinity(entries, genre_counts)
    seed_weights = {entry.media_id: signal_weight(entry) for entry in entries}

    scored: list[Scored] = []
    for candidate in candidates:
        provider_signal = 0.0
        best: tuple[float, uuid.UUID] | None = None
        for edge in candidate.edges:
            contribution = seed_weights.get(edge.seed_media_id, 0.0) * (RR_NUMERATOR / (1 + edge.position))
            provider_signal += contribution
            # `edge.seed_media_id.bytes` is the tiebreaker, so an exact tie between two seeds
            # resolves the same way on every run rather than by dict ordering.
            key = (contribution, edge.seed_media_id.bytes)
            if best is None or key > (best[0], best[1].bytes):
                best = (contribution, edge.seed_media_id)
        if best is None:
            continue

        match, matched = _genre_match(candidate.genres, affinity)
        scored.append(
            Scored(
                media_id=candidate.media_id,
                score=provider_signal * (ALPHA + (1 - ALPHA) * match),
                seed_media_id=best[1],
                matched_genres=matched,
            )
        )

    scored.sort(key=lambda s: (-s.score, s.media_id.bytes))
    return scored
