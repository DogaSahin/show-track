import asyncio
import threading
from datetime import UTC, datetime, timedelta

import pytest
from httpx import AsyncClient
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.users import security, service, throttle
from app.users.models import RefreshToken

VALID = {"username": "doga", "email": "doga@example.com", "password": "correct horse battery staple"}
SECOND_USER = {"username": "kai", "email": "kai@example.com", "password": "another correct horse battery staple"}


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
    hashes_checked = []
    original_verify = service.security.verify_password

    def counting_verify(hashed: str, password: str) -> bool:
        hashes_checked.append(hashed)
        return original_verify(hashed, password)

    monkeypatch.setattr(service.security, "verify_password", counting_verify)

    response = await client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong"})

    assert response.status_code == 401
    # Not just "a verify happened": it must be checked against DUMMY_PASSWORD_HASH specifically.
    # A malformed placeholder would also satisfy a bare call count — verify_password returns
    # False for it in microseconds (InvalidHashError, caught) — while silently restoring the
    # timing gap this test exists to prevent.
    assert hashes_checked == [service.security.DUMMY_PASSWORD_HASH]


async def _login(client: AsyncClient) -> dict[str, str]:
    await client.post("/v1/auth/register", json=_register_body())
    response = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": VALID["password"]})
    return response.json()


async def _login_second_user(client: AsyncClient) -> dict[str, str]:
    """A distinct account, so a cascade test can tell "scoped to one family" apart from
    "scoped to one user's only family" — the latter is indistinguishable from a table-wide
    wipe when the suite only ever has one user in it.
    """
    body = {**SECOND_USER, "invite_code": get_settings().registration_code}
    await client.post("/v1/auth/register", json=body)
    response = await client.post(
        "/v1/auth/login", json={"email": SECOND_USER["email"], "password": SECOND_USER["password"]}
    )
    return response.json()


async def test_refresh_returns_a_new_pair(client: AsyncClient) -> None:
    tokens = await _login(client)

    response = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 200
    assert response.json()["refresh_token"] != tokens["refresh_token"]


async def test_the_old_refresh_token_stops_working(client: AsyncClient) -> None:
    tokens = await _login(client)
    await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})

    replay = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})

    assert replay.status_code == 401


async def test_replaying_a_revoked_token_revokes_the_whole_family(client: AsyncClient) -> None:
    """The reason refresh tokens are stored at all. A revoked token being presented means
    someone replayed a stolen one, so every token in the chain dies and the user must log in
    again — including the token the legitimate client is currently holding.
    """
    first = await _login(client)
    second = (await client.post("/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})).json()

    # The thief replays the old one.
    replay = await client.post("/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert replay.status_code == 401

    # The legitimate client's current token is now dead too.
    legitimate = await client.post("/v1/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert legitimate.status_code == 401


async def test_logout_revokes_the_token(client: AsyncClient) -> None:
    tokens = await _login(client)

    logout = await client.post("/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]})
    assert logout.status_code == 204

    after = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert after.status_code == 401


async def test_logout_is_idempotent_and_does_not_leak(client: AsyncClient) -> None:
    """204 for an unknown token as well as a known one: retries must not fail, and the status
    must not reveal whether the token existed.
    """
    tokens = await _login(client)
    await client.post("/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]})

    again = await client.post("/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]})
    unknown = await client.post("/v1/auth/logout", json={"refresh_token": "never-issued"})

    assert again.status_code == 204
    assert unknown.status_code == 204


async def test_the_cascade_is_scoped_to_the_replayed_tokens_family(client: AsyncClient) -> None:
    """The cascade must revoke only the family the replayed token belongs to, not every
    unrevoked refresh token in the table. With a single account in play, "this family" and
    "every family" are indistinguishable — a second, unrelated account is what tells them
    apart. Without the `family_id` filter, one attacker replaying their own stale token would
    log out every user in the system.
    """
    victim = await _login(client)
    bystander = await _login_second_user(client)

    await client.post("/v1/auth/refresh", json={"refresh_token": victim["refresh_token"]})
    # The thief replays the victim's old token, triggering a cascade — but only in the
    # victim's family.
    replay = await client.post("/v1/auth/refresh", json={"refresh_token": victim["refresh_token"]})
    assert replay.status_code == 401

    unaffected = await client.post("/v1/auth/refresh", json={"refresh_token": bystander["refresh_token"]})
    assert unaffected.status_code == 200


async def test_an_expired_token_is_refused_without_revoking_its_family(
    client: AsyncClient, db_session: AsyncSession
) -> None:
    """Expiry alone is not evidence of theft, so it must not trigger the reuse cascade the way
    a revoked-token replay does. A second, still-live token is placed in the same family by
    hand (rotation normally leaves only one live token per family) — if expiry incorrectly
    ran the cascade, this is the row that would prove it by dying too.
    """
    tokens = await _login(client)
    stored = (
        await db_session.execute(
            select(RefreshToken).where(
                RefreshToken.token_hash == service.security.hash_refresh_token(tokens["refresh_token"])
            )
        )
    ).scalar_one()

    sibling_raw = service.security.generate_refresh_token()
    db_session.add(
        RefreshToken(
            user_id=stored.user_id,
            family_id=stored.family_id,
            token_hash=service.security.hash_refresh_token(sibling_raw),
            expires_at=datetime.now(UTC) + timedelta(days=1),
        )
    )
    await db_session.execute(
        update(RefreshToken)
        .where(RefreshToken.id == stored.id)
        .values(expires_at=datetime.now(UTC) - timedelta(days=1))
    )
    await db_session.flush()

    expired = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert expired.status_code == 401

    sibling = await client.post("/v1/auth/refresh", json={"refresh_token": sibling_raw})
    assert sibling.status_code == 200


async def test_the_sixth_attempt_is_refused_even_with_the_right_password(client: AsyncClient) -> None:
    await client.post("/v1/auth/register", json=_register_body())
    for _ in range(5):
        wrong = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": "wrong-password"})
        assert wrong.status_code == 401

    response = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": VALID["password"]})

    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) >= 1


async def test_the_lockout_looks_the_same_for_known_and_unknown_emails(client: AsyncClient) -> None:
    """The throttle must not become an account-existence oracle: an email with no account is
    counted and refused exactly like one that has an account."""
    await client.post("/v1/auth/register", json=_register_body())

    async def six_wrong(email: str) -> list:
        return [
            await client.post("/v1/auth/login", json={"email": email, "password": "wrong-password"}) for _ in range(6)
        ]

    known, unknown = await six_wrong(VALID["email"]), await six_wrong("nobody@example.com")

    assert [r.status_code for r in known] == [r.status_code for r in unknown] == [401] * 5 + [429]
    assert known[-1].json()["detail"] == unknown[-1].json()["detail"]
    assert int(known[-1].headers["Retry-After"]) >= 1
    assert int(unknown[-1].headers["Retry-After"]) >= 1


async def test_a_successful_login_clears_earlier_failures(client: AsyncClient) -> None:
    await client.post("/v1/auth/register", json=_register_body())
    bad = {"email": VALID["email"], "password": "wrong-password"}
    for _ in range(4):
        await client.post("/v1/auth/login", json=bad)
    assert (
        await client.post("/v1/auth/login", json={"email": VALID["email"], "password": VALID["password"]})
    ).status_code == 200
    for _ in range(5):
        assert (await client.post("/v1/auth/login", json=bad)).status_code == 401


async def test_an_overlong_login_password_is_a_422(client: AsyncClient) -> None:
    response = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": "p" * 257})
    assert response.status_code == 422


async def test_argon2_never_runs_on_the_event_loop_thread(client: AsyncClient, monkeypatch) -> None:
    """Register (hash), a known login (verify) and an unknown one (the dummy verify) — the last is
    the path most likely to be left on the loop.
    """
    threads: list[threading.Thread] = []
    real = security._hasher

    class Spy:
        def hash(self, *args):
            threads.append(threading.current_thread())
            return real.hash(*args)

        def verify(self, *args):
            threads.append(threading.current_thread())
            return real.verify(*args)

    monkeypatch.setattr(security, "_hasher", Spy())

    await client.post("/v1/auth/register", json=_register_body())
    await client.post("/v1/auth/login", json={"email": VALID["email"], "password": VALID["password"]})
    await client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": "whatever"})

    assert len(threads) == 3
    assert all(thread is not threading.main_thread() for thread in threads)


async def test_a_busy_hashing_queue_is_a_503_and_does_not_count_as_an_attempt(client: AsyncClient, monkeypatch) -> None:
    monkeypatch.setattr(security, "ARGON2_MAX_PENDING", 0)
    for _ in range(6):
        response = await client.post("/v1/auth/login", json={"email": VALID["email"], "password": "x"})
        assert response.status_code == 503
        assert response.headers["Retry-After"] == "1"


async def test_concurrent_attempts_cannot_overrun_the_limit(monkeypatch) -> None:
    """Service level: the `client` fixture shares one DB connection, so route calls can't run
    concurrently. The property is synchronous (begin() has no await), so a stub is enough.
    """
    calls = 0

    async def stub_authenticate(session, *, email, password):
        nonlocal calls
        calls += 1
        await asyncio.sleep(0)
        return None

    monkeypatch.setattr(service, "authenticate", stub_authenticate)

    results = await asyncio.gather(
        *(service.login(None, email="a@example.com", password="x", now=1000.0) for _ in range(20)),
        return_exceptions=True,
    )

    assert calls == 5
    assert sum(isinstance(result, service.LoginThrottled) for result in results) == 15


async def test_a_login_cancelled_before_its_answer_does_not_count_as_an_attempt(monkeypatch) -> None:
    """A client that disconnects while its request waits for an argon2 slot never had a password
    checked, so the attempt it recorded is given back."""
    monkeypatch.setattr(throttle, "login_throttle", throttle.LoginThrottle(max_attempts=1))
    entered = asyncio.Event()

    async def stub_authenticate(session, *, email, password):
        entered.set()
        await asyncio.Event().wait()  # never answers: stands in for waiting on a hashing slot

    monkeypatch.setattr(service, "authenticate", stub_authenticate)

    task = asyncio.create_task(service.login(None, email="a@example.com", password="x", now=1000.0))
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert throttle.login_throttle.begin(throttle.key_for("a@example.com"), 1000.0) is None
