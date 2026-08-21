import uuid

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
