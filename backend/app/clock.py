from datetime import UTC, datetime


def utcnow() -> datetime:
    """Current UTC time. Callers go through this module so tests can pin the clock."""
    return datetime.now(UTC)
