"""Minimal *valid* instances, so each test states only the field it is about."""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Episode, Media, MediaSource, MediaStatus, MediaType
from app.notifications.models import NotificationPrefs, NotificationTask, NotificationThreshold
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


def make_episode(media_id: uuid.UUID, **overrides: object) -> Episode:
    defaults: dict[str, object] = {"media_id": media_id, "season_number": 1, "number": 1}
    return Episode(**{**defaults, **overrides})


def make_user_media(user_id: uuid.UUID, media_id: uuid.UUID, **overrides: object) -> UserMedia:
    defaults: dict[str, object] = {
        "user_id": user_id,
        "media_id": media_id,
        "status": UserMediaStatus.WATCHING,
    }
    return UserMedia(**{**defaults, **overrides})


def make_notification_prefs(user_id: uuid.UUID, **overrides: object) -> NotificationPrefs:
    defaults: dict[str, object] = {"user_id": user_id}
    return NotificationPrefs(**{**defaults, **overrides})


def make_notification_task(user_id: uuid.UUID, media_id: uuid.UUID, **overrides: object) -> NotificationTask:
    defaults: dict[str, object] = {
        "user_id": user_id,
        "media_id": media_id,
        "episode_number": 1,
        "threshold": NotificationThreshold.TWENTY_FOUR_HOURS,
    }
    return NotificationTask(**{**defaults, **overrides})


async def make_parents(db_session: AsyncSession) -> tuple[User, Media]:
    """A flushed `User` + `Media` pair, for tests whose subject is a row that FKs to both.

    Promoted from `test_library_model.py`'s local `_entry_parents` once
    `test_notifications_model.py` needed the identical setup, so the two modules share
    one definition instead of a second copy drifting from the first.
    """
    user = make_user()
    media = make_media()
    db_session.add(user)
    db_session.add(media)
    await db_session.flush()
    return user, media
