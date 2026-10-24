from pydantic import BaseModel


class AppRelease(BaseModel):
    """The latest published build of the Android app, as the update prompt needs it."""

    version_code: int
    version_name: str
    # Installed builds below this must update before continuing; at or above it the update is optional.
    min_version_code: int
    notes: str
    size_bytes: int
    sha256: str
