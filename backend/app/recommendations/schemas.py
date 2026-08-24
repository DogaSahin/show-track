import uuid

from pydantic import BaseModel

from app.media.schemas import MediaDetail


class SeedSummary(BaseModel):
    """`ran` is False exactly when the advisory lock was held elsewhere, and every count is then
    zero — the same distinction SyncSummary draws, for the same reason: "another replica is doing
    it" and "there was nothing to do" must not look alike in a log.
    """

    ran: bool
    # Seed titles considered due this sweep.
    seeds: int = 0
    # Seeds the provider actually answered for.
    fetched: int = 0
    # Candidate titles that had no `media` row and were resolved through get_many.
    new_media: int = 0
    # Similarity edges written or refreshed.
    edges: int = 0
    # Seeds whose provider raised, or whose source has no provider registered. Counted per seed,
    # so the summary reflects how much of the pool went unrefreshed.
    failed: int = 0


class RecommendationReason(BaseModel):
    """Why this title is here. The single most valuable thing a small recommender can offer:
    it turns an unexplained list into one the user can disagree with.

    ONE seed, not all of them — the ranking sums every seed's contribution, but "because you liked
    these four things a little" is not something anyone can act on, so the reason names the
    strongest single contributor.
    """

    seed_media_id: uuid.UUID
    seed_title: str
    matched_genres: list[str]


class RecommendationItem(BaseModel):
    """MediaDetail is embedded rather than referenced, exactly as LibraryEntry does it: a list
    screen needs titles and cover art, and an id-only response makes rendering one page N+1
    requests.

    There is deliberately NO score field. Publishing the blended float would let clients render a
    number whose scale was never defined, making every retune of the weights a visible,
    unexplainable change (decision 7-K). The ordering IS the score.
    """

    media: MediaDetail
    reason: RecommendationReason


class RecommendationPage(BaseModel):
    items: list[RecommendationItem]
    next_cursor: str | None
