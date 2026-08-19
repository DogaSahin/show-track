import pytest
from pydantic import ValidationError

from app.config import Settings


def test_settings_requires_database_url(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    """An unset DATABASE_URL must fail loudly at startup.

    `chdir` to an empty directory as well as clearing the variable: Settings reads
    `env_file=".env"` relative to the working directory, so without this the real
    backend/.env would satisfy the field and the test would pass for the wrong reason.

    `match` scopes the assertion to this field. Unscoped, the test would keep passing once
    Phase 2 adds a second required setting, while no longer proving anything about
    DATABASE_URL — Pydantic names the offending field in the message, so matching on it
    keeps the test tied to what it claims to guard.
    """
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.chdir(tmp_path)

    with pytest.raises(ValidationError, match="database_url"):
        Settings()


def test_tmdb_api_key_defaults_to_none(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    """Optional by design — a fresh clone must boot without a TMDB signup.

    Same chdir-to-an-empty-directory guard as test_settings_requires_database_url above:
    Settings reads `env_file=".env"` relative to the working directory, so without it the real
    backend/.env would supply a value and this would pass or fail for the wrong reason.
    """
    monkeypatch.delenv("TMDB_API_KEY", raising=False)
    monkeypatch.chdir(tmp_path)

    settings = Settings(database_url="postgresql+asyncpg://x/y", secret_key="s", registration_code="r")

    assert settings.tmdb_api_key is None
