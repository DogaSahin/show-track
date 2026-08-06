from httpx import AsyncClient


async def test_health_returns_ok(client: AsyncClient) -> None:
    response = await client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_health_is_not_versioned(client: AsyncClient) -> None:
    """/health is an infra probe, not client contract — it must not sit under /v1."""
    response = await client.get("/v1/health")

    assert response.status_code == 404


async def test_openapi_lists_only_versioned_routes(client: AsyncClient) -> None:
    """Every documented route except /health lives under /v1."""
    response = await client.get("/openapi.json")
    paths = response.json()["paths"].keys()

    non_versioned = [p for p in paths if not p.startswith("/v1") and p != "/health"]
    assert non_versioned == []
