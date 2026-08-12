"""Minimal *valid* instances, so each test states only the field it is about."""

from app.users.models import User


def make_user(**overrides: object) -> User:
    defaults: dict[str, object] = {
        "username": "doga",
        "email": "doga@example.com",
        "hashed_password": "not-a-real-hash",
    }
    return User(**{**defaults, **overrides})
