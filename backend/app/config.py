from functools import lru_cache

from pydantic import Field
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
    # All defaulted, never required. CLAUDE.md records that Phase 2 added two REQUIRED settings
    # and broke backend-ci on every subsequent PR.
    #
    # sync_enabled is more than an off switch: it is the documented way to run a SECOND REPLICA —
    # scheduler off, API on — which is precisely the deployment question the advisory lock exists
    # to answer. The lock protects against the mistake; this setting is how you avoid making it.
    sync_enabled: bool = True
    # ge=1 because IntervalTrigger raises "The time interval must be positive" for zero — inside
    # lifespan, so the app would not boot. "Defaulted" is not the same as "safe when set wrong",
    # and an env typo is the likeliest way this gets set.
    # Default 1, not 6: app/sync/service.py tiers the refresh cadence per title, and the job can
    # only honour its tightest tier if it runs at least that often. Raising this above the
    # tightest tier in SYNC_TIERS silently widens every tier to this value — the job cannot
    # refresh a title it is not awake to look at.
    sync_interval_hours: int = Field(default=1, ge=1)
    threshold_scan_minutes: int = Field(default=15, ge=1)
    # The lead time for the AIRING_SOON notification threshold. le=24 is not decoration: the
    # threshold scan's SQL prefilter is a hard 24-hour window, so a larger lead time would
    # silently drop candidates the threshold should have caught — the same "never enqueued and
    # indistinguishable from a healthy quiet scan" failure the lead-time rule replaced.
    notify_soon_hours: int = Field(default=6, ge=1, le=24)
    # Optional, like TMDB_API_KEY and for the same recorded reason: a required setting breaks
    # backend-ci on every subsequent PR. Absent means no transport is registered, the dispatch
    # job is never scheduled, and tasks queue harmlessly.
    ntfy_base_url: str | None = None
    # A CREDENTIAL. Same category as SECRET_KEY: never logged, never echoed in an error body.
    ntfy_token: str | None = None
    # The dispatcher is a single indexed query when the queue is empty, which is almost always,
    # and its latency sits directly on top of the threshold scan's 15-minute granularity.
    notification_dispatch_minutes: int = Field(default=1, ge=1)
    # Upstream similar-to lists move on the scale of weeks, so this is generous rather than tight —
    # contrast sync_interval_hours, where airing times are time-critical.
    recommendations_seed_hours: int = Field(default=12, ge=1)
    # A BACKSTOP, not the primary invalidation. The library-change checks in
    # recommendations.service cover the inputs that matter; this TTL covers only the two that have
    # no cheap trigger — a sync refresh changing a candidate's genres, and the global genre counts
    # behind IDF drifting as `media` grows. Both move slowly.
    recommendations_ttl_hours: int = Field(default=24, ge=1)
    log_level: str = "INFO"
    environment: str = "local"


@lru_cache
def get_settings() -> Settings:
    return Settings()
