"""Minimal *valid* instances, so each test states only the field it is about."""

from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.users.models import User


def make_user(**overrides: object) -> User:
    defaults: dict[str, object] = {
        "username": "doga",
        "email": "doga@example.com",
        "hashed_password": "not-a-real-hash",
    }
    return User(**{**defaults, **overrides})


def make_media(**overrides: object) -> Media:
    defaults: dict[str, object] = {
        "type": MediaType.ANIME,
        "source": MediaSource.ANILIST,
        "external_id": "16498",
        "title": "Shingeki no Kyojin",
        "genres": ["Action", "Drama"],
        "status": MediaStatus.FINISHED,
    }
    return Media(**{**defaults, **overrides})
