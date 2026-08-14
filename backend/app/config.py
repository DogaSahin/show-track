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
    access_token_ttl_minutes: int = 30
    refresh_token_ttl_days: int = 30
    log_level: str = "INFO"
    environment: str = "local"


@lru_cache
def get_settings() -> Settings:
    return Settings()
