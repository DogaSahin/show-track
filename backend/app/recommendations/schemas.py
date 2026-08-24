from pydantic import BaseModel


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
