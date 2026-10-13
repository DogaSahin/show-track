import pytest
from pydantic import ValidationError

from app.config import Settings
from tests.factories import TEST_REGISTRATION_CODE, TEST_SECRET_KEY, valid_settings


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

    settings = Settings(
        database_url="postgresql+asyncpg://x/y", secret_key=TEST_SECRET_KEY, registration_code=TEST_REGISTRATION_CODE
    )

    assert settings.tmdb_api_key is None


@pytest.mark.parametrize("placeholder", ["change-me-use-openssl-rand-hex-32", "change-me", "showtrack"])
@pytest.mark.parametrize("field", ["secret_key", "registration_code"])
def test_example_placeholders_are_rejected(field: str, placeholder: str) -> None:
    with pytest.raises(ValidationError, match="openssl rand -hex 32"):
        valid_settings(**{field: placeholder})


def test_secret_key_needs_32_characters() -> None:
    with pytest.raises(ValidationError, match="at least 32"):
        valid_settings(secret_key="k" * 31)
    assert valid_settings(secret_key="k" * 32).secret_key == "k" * 32


def test_registration_code_needs_16_characters() -> None:
    with pytest.raises(ValidationError, match="at least 16"):
        valid_settings(registration_code="c" * 15)
    assert valid_settings(registration_code="c" * 16).registration_code == "c" * 16


def test_rejection_does_not_echo_the_value() -> None:
    """Startup errors land in container logs; a near-miss real secret must not be printed."""
    near_miss = "a-real-looking-but-short-secret"
    with pytest.raises(ValidationError) as excinfo:
        valid_settings(secret_key=near_miss)
    assert near_miss not in str(excinfo.value)
