import uuid

import pytest
from sqlalchemy import func, select

from app.notifications.models import PushTarget
from tests.conftest import PUSH_ENDPOINT
from tests.factories import make_push_target, make_user


async def test_creating_a_target_returns_a_generated_topic(auth_client):
    """The server generates the topic; the client never supplies one (6-L). A client-chosen topic
    would be guessable, and an ntfy topic is a bearer secret in both directions — whoever knows it
    reads every notification on it AND can post arbitrary ones to that phone.
    """
    response = await auth_client.post("/v1/notifications/targets", json={"label": "Pixel 8"})

    assert response.status_code == 201
    body = response.json()
    assert body["label"] == "Pixel 8"
    assert body["transport"] == "ntfy"
    # token_urlsafe(32) is 43 characters. Asserting a floor rather than the exact length keeps
    # this from breaking if the entropy is raised, while still failing loudly if someone swaps in
    # something guessable.
    assert len(body["target"]) >= 32


async def test_two_registrations_get_different_topics(auth_client):
    first = (await auth_client.post("/v1/notifications/targets", json={"label": "phone"})).json()
    second = (await auth_client.post("/v1/notifications/targets", json={"label": "tablet"})).json()

    assert first["target"] != second["target"]


async def test_listing_targets_never_returns_the_topic(auth_client):
    """The topic is shown exactly once, at creation, the way an API key is. An endpoint that hands
    it back on demand turns any read-only token leak into a notification-stream compromise.

    This is a security property a well-meaning "make the API more useful" change would quietly
    undo, which is precisely why it has its own test.
    """
    created = (await auth_client.post("/v1/notifications/targets", json={"label": "phone"})).json()

    listed = (await auth_client.get("/v1/notifications/targets")).json()

    assert len(listed) == 1
    assert listed[0]["id"] == created["id"]
    assert "target" not in listed[0]


async def test_deleting_a_target_removes_it(auth_client):
    created = (await auth_client.post("/v1/notifications/targets", json={"label": "phone"})).json()

    response = await auth_client.delete(f"/v1/notifications/targets/{created['id']}")

    assert response.status_code == 204
    assert (await auth_client.get("/v1/notifications/targets")).json() == []


async def test_a_user_cannot_delete_another_users_target(auth_client, db_session):
    """404, not 403: confirming the id exists would leak that another account owns it."""
    other = make_user(username="other", email="other@example.com")
    db_session.add(other)
    await db_session.flush()
    target = make_push_target(other.id, target="someone-elses-topic")
    db_session.add(target)
    await db_session.flush()

    response = await auth_client.delete(f"/v1/notifications/targets/{target.id}")

    assert response.status_code == 404


async def test_deleting_a_nonexistent_target_is_404(auth_client):
    assert (await auth_client.delete(f"/v1/notifications/targets/{uuid.uuid4()}")).status_code == 404


async def test_deleting_a_target_without_a_token_is_rejected(client):
    """test_auth_protection.py's generic sweep already covers this route's mount-level
    dependency (that check needs no id, so it runs even for `{param}` paths) — but it skips the
    HTTP-level 401 request for any path with a param, since that DOES need a real id. So the
    actual "does an anonymous DELETE get rejected" behaviour is asserted here or nowhere.

    Takes `client`, NEVER `auth_client`: the authenticated fixture sets an Authorization header
    that would make this pass regardless.
    """
    assert (await client.delete(f"/v1/notifications/targets/{uuid.uuid4()}")).status_code == 401


async def test_supplying_a_target_is_rejected_rather_than_ignored(auth_client):
    """The topic must be server-minted and unguessable, so a client-supplied one is refused
    outright rather than silently dropped. Without extra="forbid" this body 201s with the
    attacker's value discarded — harmless in outcome, but it pins the schema's field list rather
    than the guarantee.
    """
    response = await auth_client.post("/v1/notifications/targets", json={"label": "phone", "target": "guessable-topic"})

    assert response.status_code == 422


# --------------------------------------------------------------------------------------
# UnifiedPush registration (decisions A-K, A-L, A-O)
# --------------------------------------------------------------------------------------


async def test_an_ntfy_registration_still_rejects_a_supplied_target(auth_client):
    """6-L SCOPED, not weakened (decision A-K). `target` is now a real field on TargetCreate —
    UnifiedPush needs it, because there the distributor mints the endpoint and the server cannot
    — so `extra="forbid"` no longer carries this guarantee on its own. Deleting the validator
    leaves a schema that accepts a client-chosen ntfy TOPIC, which is a guessable bearer secret
    with read AND write access to that phone's notification stream.
    """
    response = await auth_client.post(
        "/v1/notifications/targets", json={"transport": "ntfy", "target": "guessable-topic"}
    )

    assert response.status_code == 422


async def test_a_unifiedpush_registration_without_a_target_is_rejected(auth_client):
    """The other half of the same validator. Defaulting a missing endpoint to something — an
    empty string, the ntfy path — would store a row the dispatcher can never deliver to and that
    nothing would ever diagnose.
    """
    response = await auth_client.post("/v1/notifications/targets", json={"transport": "unifiedpush"})

    assert response.status_code == 422


async def test_an_endpoint_off_the_configured_server_is_rejected(auth_client, configured_push):
    """Decision A-L, at the boundary. Stored unchecked, this endpoint becomes an instruction to
    the dispatcher to POST a body of our choosing — with the ntfy credential attached — to a host
    of the attacker's choosing, once per matching episode, forever.
    """
    response = await auth_client.post(
        "/v1/notifications/targets", json={"transport": "unifiedpush", "target": "https://evil.example/UPtopic"}
    )

    assert response.status_code == 422


async def test_registering_the_same_endpoint_twice_creates_one_row(auth_client, db_session, configured_push):
    """Decision A-O. `onNewEndpoint` fires on EVERY app start, so the client cannot avoid
    re-registering — which is why the second call is a 200 and not a 409. The status code is the
    only thing that differs; both return the same body.
    """
    body = {"transport": "unifiedpush", "target": PUSH_ENDPOINT, "label": "Pixel 8"}

    first = await auth_client.post("/v1/notifications/targets", json=body)
    second = await auth_client.post("/v1/notifications/targets", json=body)

    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]
    assert first.json()["transport"] == "unifiedpush"
    # ARCHITECTURE RULE 8: asserted through Core, never `session.get`. A session's identity map is
    # invalidated only by that session's OWN ORM writes, so an assertion routed through the map
    # can be answered from cache and report one row while the database holds two — a guaranteed
    # false green, and precisely the bug this test exists to catch.
    #
    # It carries no weight against any mutation of THIS code, and that is worth stating rather
    # than leaving for the next reader to rediscover: `uq_push_targets_transport_target` is
    # global on (transport, target) (6-D), so any second row with this endpoint is an
    # IntegrityError before a count could ever read 2. The assertion starts earning its keep the
    # day that constraint is narrowed to per-user — which is exactly when someone would delete it
    # for looking redundant.
    assert await db_session.scalar(select(func.count()).select_from(PushTarget)) == 1


async def test_a_previous_users_endpoint_is_taken_over_not_refused(auth_client, auth_user, db_session, configured_push):
    """THE DEVICE-HANDOVER CASE, and the exact state the app is left in when it cannot clean up
    after itself: the previous account's row survives because the logout DELETE could not
    authenticate — a terminal refresh failure is how the app learns it is logged out, so the token
    is already dead. A 409 here is a permanent dead end for the person holding the phone.

    Possession of the endpoint IS the device credential: the distributor mints it per app per
    device and ntfy delivers by topic to whoever subscribes, so refusing takes nothing away from
    anyone who already has the string. 200 with the row reassigned.
    """
    other = make_user(username="other-up", email="other-up@example.com")
    db_session.add(other)
    await db_session.flush()
    stranded = make_push_target(other.id, transport="unifiedpush", target=PUSH_ENDPOINT)
    db_session.add(stranded)
    await db_session.flush()

    response = await auth_client.post(
        "/v1/notifications/targets", json={"transport": "unifiedpush", "target": PUSH_ENDPOINT}
    )

    assert response.status_code == 200
    assert response.json()["id"] == str(stranded.id)
    # ARCHITECTURE RULE 8: through Core, never session.get(). Measured to be hygiene here too —
    # the insert-instead-of-update mutation dies on the unique constraint, and with the constraint
    # dropped it dies on the id assertion below. See test_unifiedpush.py for the full note.
    assert await db_session.scalar(select(func.count()).select_from(PushTarget)) == 1
    # The row is genuinely the caller's now, not merely returned to them.
    owner = await db_session.scalar(select(PushTarget.user_id).where(PushTarget.id == stranded.id))
    assert owner == auth_user.id


async def test_listing_still_withholds_a_unifiedpush_endpoint(auth_client, configured_push):
    """The endpoint is a bearer secret exactly as the ntfy topic is, and TargetRead withholds it
    for the same reason — an endpoint handed back on demand turns a read-only token leak into the
    ability to push arbitrary notifications to that device.
    """
    await auth_client.post("/v1/notifications/targets", json={"transport": "unifiedpush", "target": PUSH_ENDPOINT})

    listed = (await auth_client.get("/v1/notifications/targets")).json()

    assert len(listed) == 1
    assert "target" not in listed[0]


async def test_an_over_long_endpoint_is_a_422_rather_than_a_500(auth_client, configured_push):
    """The origin check pins the HOST and says nothing about the path. Without a length bound, a
    10KB path on the right host passes every validator and then fails at INSERT with a Postgres
    22001 — a 500 for what is plainly a bad request.
    """
    long_endpoint = f"{PUSH_ENDPOINT}/{'x' * 300}"

    response = await auth_client.post(
        "/v1/notifications/targets", json={"transport": "unifiedpush", "target": long_endpoint}
    )

    assert response.status_code == 422


@pytest.mark.parametrize(
    ("endpoint", "why"),
    [
        ("https://[evil/x", "urlparse raises ValueError('Invalid IPv6 URL') on an unclosed literal"),
        ("https://push.example℀test/upTopic", "urlparse refuses an NFKC-unsafe netloc"),
    ],
)
async def test_an_unparseable_endpoint_is_a_422_rather_than_a_500(auth_client, configured_push, endpoint, why):
    """`urlparse` RAISES on some inputs rather than returning a useless parse, and `app/errors.py`
    registers no handler for `ValueError` — so unwrapped these are a 500 for what is plainly a bad
    request. Same class as the unbounded `target` length above; both are closed at the boundary.
    """
    response = await auth_client.post(
        "/v1/notifications/targets", json={"transport": "unifiedpush", "target": endpoint}
    )

    assert response.status_code == 422


async def test_ntfys_own_account_api_cannot_be_registered_as_an_endpoint(auth_client, configured_push):
    """The check that stops an origin match from being the whole story. This URL is on the
    configured server, so scheme and netloc both match — and it is ntfy's ACCOUNT API, which is
    exactly where NTFY_TOKEN is privileged. Registered, the dispatcher would POST to it bearing
    that credential once per matching episode.
    """
    response = await auth_client.post(
        "/v1/notifications/targets",
        json={"transport": "unifiedpush", "target": "https://push.example.test/v1/account/token"},
    )

    assert response.status_code == 422
