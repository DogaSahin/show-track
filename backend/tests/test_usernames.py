import importlib.util
from pathlib import Path

import pytest
from httpx import AsyncClient

from app.config import get_settings
from app.users.schemas import normalise_username


def _register(username: str, n: int) -> dict[str, object]:
    return {
        "username": username,
        "email": f"user{n}@example.com",
        "password": "correct horse battery staple",
        "invite_code": get_settings().registration_code,
    }


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Alice ", "Alice"),
        ("Alice\t", "Alice"),
        ("Alice　", "Alice"),  # ideographic space
        ("ａｌｉｃｅ", "alice"),  # fullwidth
        ("alice​", "alice"),  # zero-width space
        ("e‍́", "é"),  # invisible char must not block composition
    ],
)
def test_normalise_username(raw: str, expected: str) -> None:
    assert normalise_username(raw) == expected


@pytest.mark.parametrize("second", ["Alice", "alice ", "ALICE　", "ａｌｉｃｅ", "alice​"])
async def test_near_copies_of_a_username_are_rejected(client: AsyncClient, second: str) -> None:
    assert (await client.post("/v1/auth/register", json=_register("alice", 1))).status_code == 201
    assert (await client.post("/v1/auth/register", json=_register(second, 2))).status_code == 409


async def test_the_stored_username_is_the_normalised_form(client: AsyncClient) -> None:
    response = await client.post("/v1/auth/register", json=_register("  Doga​ ", 1))
    assert response.json()["username"] == "Doga"


@pytest.mark.parametrize("username", ["   ", "​", 5])
async def test_blank_or_non_string_usernames_are_a_422(client: AsyncClient, username: object) -> None:
    assert (await client.post("/v1/auth/register", json=_register(username, 1))).status_code == 422


def _migration():
    path = next(Path(__file__).parents[1].glob("migrations/versions/*-0019_*.py"))
    spec = importlib.util.spec_from_file_location("migration_0019", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_check_reports_collisions_blanks_and_overlength() -> None:
    find_problems = _migration().find_problems
    assert find_problems([(1, "alice"), (2, "Bob")]) == []
    assert any("alice" in p and "Alice " in p for p in find_problems([(1, "alice"), (2, "Alice ")]))
    assert find_problems([(1, "​ ")])  # blank once normalised
    assert find_problems([(1, "ﷺ" * 4)])  # NFKC expands past 64


def test_the_migration_normaliser_matches_the_app() -> None:
    frozen = _migration()._normalise
    for raw in ["Alice ", "ａｌｉｃｅ", "alice​", "e‍́", "Ünïcödé\t"]:
        assert frozen(raw) == normalise_username(raw)
