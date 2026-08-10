import pytest
from pydantic import ValidationError

from app.config import Settings


def test_settings_requires_database_url(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    """An unset DATABASE_URL must fail loudly at startup.

    `chdir` to an empty directory as well as clearing the variable: Settings reads
    `env_file=".env"` relative to the working directory, so without this the real
    backend/.env would satisfy the field and the test would pass for the wrong reason.
    """
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.chdir(tmp_path)

    with pytest.raises(ValidationError):
        Settings()
