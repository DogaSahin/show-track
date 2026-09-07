async def test_a_user_with_no_prefs_row_reads_as_disabled(auth_client):
    """Phase 5's threshold scan inner-joins notification_prefs, so an absent row already means
    "no notifications". Reporting true here would make the API contradict the scan.
    """
    response = await auth_client.get("/v1/notifications/prefs")

    assert response.status_code == 200
    assert response.json() == {"push_enabled": False}


async def test_enabling_creates_the_row(auth_client):
    response = await auth_client.patch("/v1/notifications/prefs", json={"push_enabled": True})

    assert response.status_code == 200
    assert response.json() == {"push_enabled": True}
    assert (await auth_client.get("/v1/notifications/prefs")).json() == {"push_enabled": True}


async def test_disabling_an_existing_row_updates_it_in_place(auth_client):
    """Upsert, not insert. A second PATCH must not violate the unique constraint on user_id."""
    await auth_client.patch("/v1/notifications/prefs", json={"push_enabled": True})

    response = await auth_client.patch("/v1/notifications/prefs", json={"push_enabled": False})

    assert response.json() == {"push_enabled": False}


async def test_prefs_require_authentication(client):
    """Protection is a property of where the router is mounted. This asserts the notifications
    router actually joined the protected mounting loop in main.py.
    """
    assert (await client.get("/v1/notifications/prefs")).status_code == 401
