import pytest
from httpx import AsyncClient

from app.config import get_settings
from app.users import service

VALID = {"username": "doga", "email": "doga@example.com", "password": "correct horse battery staple"}


def _register_body(**overrides: object) -> dict[str, object]:
    body = {**VALID, "invite_code": get_settings().registration_code}
    body.update(overrides)
    return body


async def test_register_creates_an_account(client: AsyncClient) -> None:
    response = await client.post("/v1/auth/register", json=_register_body())

    assert response.status_code == 201
    body = response.json()
    assert body["username"] == "doga"
    assert body["email"] == "doga@example.com"
    assert "password" not in body
    assert "hashed_password" not in body


async def test_register_rejects_a_wrong_invite_code(client: AsyncClient) -> None:
    response = await client.post("/v1/auth/register", json=_register_body(invite_code="nope"))

    assert response.status_code == 400


async def test_register_rejects_a_missing_invite_code(client: AsyncClient) -> None:
    body = dict(VALID)

    response = await client.post("/v1/auth/register", json=body)

    assert response.status_code == 422


async def test_register_rejects_a_duplicate_email_differing_only_in_case(client: AsyncClient) -> None:
    """Phase 1 made email uniqueness case-insensitive with a functional index on lower(email);
    this is the endpoint honouring it rather than returning a 500 from the IntegrityError.
    """
    await client.post("/v1/auth/register", json=_register_body())

    response = await client.post("/v1/auth/register", json=_register_body(username="other", email="DOGA@Example.com"))

    assert response.status_code == 409


async def test_login_returns_a_token_pair(client: AsyncClient) -> None:
    await client.post("/v1/auth/register", json=_register_body())

    response = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": VALID["password"]})

    assert response.status_code == 200
    body = response.json()
    assert body["access_token"]
    assert body["refresh_token"]
    assert body["token_type"] == "bearer"


async def test_login_is_case_insensitive_on_email(client: AsyncClient) -> None:
    """Registered as doga@example.com, logs in as DOGA@EXAMPLE.COM. Requires the query to be
    `WHERE lower(email) = lower(:input)`; a plain equality lookup both misses the functional
    index and fails here.
    """
    await client.post("/v1/auth/register", json=_register_body())

    response = await client.post("/v1/auth/login", json={"email": "DOGA@EXAMPLE.COM", "password": VALID["password"]})

    assert response.status_code == 200


async def test_unknown_email_and_wrong_password_are_indistinguishable(client: AsyncClient) -> None:
    """The security property, not a nicety: if these differ, the endpoint answers "does this
    account exist?" to anyone who asks.
    """
    await client.post("/v1/auth/register", json=_register_body())

    wrong_password = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": "wrong"})
    unknown_email = await client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong"})

    assert wrong_password.status_code == unknown_email.status_code == 401
    assert wrong_password.json() == unknown_email.json()


async def test_unknown_email_still_calls_verify_password(client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    """Matching status codes and bodies (see the test above) pass identically whether or not
    the dummy verify against DUMMY_PASSWORD_HASH actually runs — both branches could just
    return 401 immediately. This asserts the mechanism itself: the unknown-email path must
    still pay for exactly one argon2 verify, not skip it.
    """
    calls = 0
    original_verify = service.security.verify_password

    def counting_verify(hashed: str, password: str) -> bool:
        nonlocal calls
        calls += 1
        return original_verify(hashed, password)

    monkeypatch.setattr(service.security, "verify_password", counting_verify)

    response = await client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong"})

    assert response.status_code == 401
    assert calls == 1
