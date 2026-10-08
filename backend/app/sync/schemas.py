from pydantic import BaseModel


class SyncSummary(BaseModel):
    """`ran` is False exactly when the advisory lock was held elsewhere, and every count is then
    zero. It is a separate field rather than a sentinel count because "another instance is doing
    it" and "there was nothing to do" are different answers that must not look alike in a log or
    a debug response.
    """

    ran: bool
    checked: int = 0
    updated: int = 0
    unchanged: int = 0
    # The provider answered but no longer knows this title. An ordinary answer, not a failure.
    missing: int = 0
    # A provider raised, or no provider is registered for that source. Counted per title, so the
    # summary reflects how much data went stale.
    failed: int = 0
    # The episode-list phase, counted per title.
    episodes_refreshed: int = 0
    episodes_failed: int = 0
