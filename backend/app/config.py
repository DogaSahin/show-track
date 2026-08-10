from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://showtrack:showtrack@localhost:5432/showtrack"
    log_level: str = "INFO"
    environment: str = "local"


@lru_cache
def get_settings() -> Settings:
    return Settings()
