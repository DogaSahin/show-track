import pytest
from pydantic import ValidationError

from app.config import Settings


def _base_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Every setting Settings requires; callers delete the one under test."""
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://u:p@localhost:5432/d")
    monkeypatch.setenv("SECRET_KEY", "test-secret")
    monkeypatch.setenv("REGISTRATION_CODE", "test-code")


def test_settings_requires_secret_key(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    """A defaulted signing key is worse than a defaulted database URL: a wrong DATABASE_URL
    fails on first query, while a default secret_key works perfectly and silently makes every
    token forgeable by anyone who has read the source.
    """
    monkeypatch.chdir(tmp_path)
    _base_env(monkeypatch)
    monkeypatch.delenv("SECRET_KEY")

    with pytest.raises(ValidationError, match="secret_key"):
        Settings()


def test_settings_requires_registration_code(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    _base_env(monkeypatch)
    monkeypatch.delenv("REGISTRATION_CODE")

    with pytest.raises(ValidationError, match="registration_code"):
        Settings()


def test_token_ttls_have_defaults(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    _base_env(monkeypatch)

    settings = Settings()

    assert settings.access_token_ttl_minutes == 30
    assert settings.refresh_token_ttl_days == 30
