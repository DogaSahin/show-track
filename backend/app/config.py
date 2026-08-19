from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    # Required, no default. A default here would work perfectly and silently make every
    # issued token forgeable by anyone who can read the source — a failure mode that,
    # unlike a wrong database URL, never announces itself.
    secret_key: str
    registration_code: str
    # Optional, unlike SECRET_KEY. Absent means the TMDB provider is never registered and
    # /v1/media/search returns AniList results only, reporting `not_configured` for TMDB in its
    # `sources` map. Required would mean CI and docker-compose both need a value or every
    # subsequent PR breaks — the exact failure Phase 2 hit. The cost of optional is that a
    # typo'd variable name degrades search silently; the startup warning in
    # app/media/providers/__init__.py and the visible `not_configured` status are the mitigation.
    tmdb_api_key: str | None = None
    access_token_ttl_minutes: int = 30
    refresh_token_ttl_days: int = 30
    log_level: str = "INFO"
    environment: str = "local"


@lru_cache
def get_settings() -> Settings:
    return Settings()
