import importlib

from httpx import AsyncClient

import main
from app.users import routes as users_routes_module


async def test_health_returns_ok(client: AsyncClient) -> None:
    response = await client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_health_is_not_versioned(client: AsyncClient) -> None:
    """/health is an infra probe, not client contract — it must not sit under /v1."""
    response = await client.get("/v1/health")

    assert response.status_code == 404


def test_include_router_mounts_domain_routers_under_v1_prefix() -> None:
    """Exercises main.py's actual mounting loop end to end, not a re-implementation of
    it: reload the real `users` domain module with a probe route attached, then reload
    main.py so it re-mounts every domain router through its real, unmodified
    `include_router(router, prefix="/v1")` call. Fails if `prefix="/v1"` is ever
    dropped from that call, or if the users router stops being included.
    """
    importlib.reload(users_routes_module)

    @users_routes_module.router.get("/__probe__")
    async def probe() -> dict[str, str]:
        return {"probe": "ok"}

    try:
        reloaded_main = importlib.reload(main)
        paths = reloaded_main.app.openapi()["paths"].keys()
        assert "/v1/users/__probe__" in paths
    finally:
        # Reload both back to a clean state so later tests (and the module-level
        # `main.app` other tests rely on) are not left with the probe route attached.
        importlib.reload(users_routes_module)
        importlib.reload(main)


def test_domain_routers_cover_all_eight_domains_with_expected_prefixes() -> None:
    """Catches a forgotten or misnamed domain router in main.DOMAIN_ROUTERS."""
    expected_prefixes = {
        "/users",
        "/media",
        "/library",
        "/reviews",
        "/sync",
        "/notifications",
        "/recommendations",
        "/groups",
    }

    actual_prefixes = {router.prefix for router in main.DOMAIN_ROUTERS}

    assert len(main.DOMAIN_ROUTERS) == 8
    assert actual_prefixes == expected_prefixes


async def test_openapi_lists_only_versioned_routes(client: AsyncClient) -> None:
    """Forward-looking guard: once Phase 1 adds real endpoints to domain routers, this
    catches any route that leaks outside /v1. On its own today it is vacuous (all
    domain routers are still empty) — real coverage of the mounting invariant lives in
    test_include_router_mounts_domain_routers_under_v1_prefix and
    test_domain_routers_cover_all_eight_domains_with_expected_prefixes above.
    """
    response = await client.get("/openapi.json")
    paths = response.json()["paths"].keys()

    non_versioned = [p for p in paths if not p.startswith("/v1") and p != "/health"]
    assert non_versioned == []
