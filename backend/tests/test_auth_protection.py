import pytest
from fastapi.routing import iter_route_contexts
from httpx import AsyncClient

from app.users.dependencies import get_current_user
from main import app

# Everything a client may reach without a token. `/v1/auth/*` is how you get one in the first
# place; `/health` is an infrastructure probe, not client contract; the rest is FastAPI's own
# documentation surface.
OPEN_PREFIXES = ("/v1/auth/", "/health", "/docs", "/redoc", "/openapi.json")


def _protected_cases() -> list[tuple[str, str, bool]]:
    """Every mounted route that should demand a token, as (method, path, mount_requires_auth).

    `app.include_router` no longer flattens routes onto `app.routes` (fastapi 0.141.1): each
    inclusion is a lazy `_IncludedRouter` wrapper, so walking `app.routes` directly and reading
    `.path`/`.methods` finds nothing behind a prefix. `iter_route_contexts` is the same
    resolution FastAPI's own OpenAPI generator uses (`fastapi/openapi/utils.py:get_openapi`) to
    get the effective, prefix-applied path and methods for every route reachable from `app`.

    `mount_requires_auth` reads `route_context.dependencies` — the router-mount-level dependency
    list built from `app.include_router(..., dependencies=[...])` — which is populated
    separately from a handler's own function-signature dependencies (those live under
    `route_context.dependant` instead; verified by inspecting `_EffectiveRouteContext.from_api_route`
    in fastapi/routing.py, which merges `include_context.dependencies` and `route.dependencies`
    into this list and leaves the handler's own `Depends(...)` parameters out of it). That
    distinction matters: `GET /v1/users/me`'s handler takes `current_user: CurrentUserDep` for
    its own reasons (it needs the `User` as data), which alone would still 401 an unauthenticated
    request even with the mount-level dependency removed — so an HTTP-level assertion by itself
    cannot tell "protected by the mount" apart from "protected by the handler". This one can, and
    does: measured empty (`[]`) for `/v1/auth/*` and containing `get_current_user` for
    `/v1/users/me`.

    Two guards against this function silently going back to collecting nothing (the exact defect
    it was written to fix): `assert found` catches `iter_route_contexts` changing shape in a way
    that empties the list outright; the canary assert catches it changing shape in a way that
    keeps the list non-empty but drops the one route this suite actually knows about.
    """
    found: list[tuple[str, str, bool]] = []
    for route_context in iter_route_contexts(app.routes):
        path = route_context.path or ""
        methods = route_context.methods or set()
        if path.startswith(OPEN_PREFIXES):
            continue
        if "{" in path:  # path params need real ids; covered by their own feature tests
            continue
        mount_requires_auth = any(dep.dependency is get_current_user for dep in route_context.dependencies)
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            found.append((method, path, mount_requires_auth))

    assert found, "collected zero protected routes — iter_route_contexts may have changed shape"
    assert ("GET", "/v1/users/me", True) in found, "the one known protected route dropped out of collection"
    return found


@pytest.mark.parametrize(("method", "path", "mount_requires_auth"), _protected_cases())
async def test_every_non_auth_route_requires_a_token(
    client: AsyncClient, method: str, path: str, mount_requires_auth: bool
) -> None:
    """Two assertions guarding two different things for every non-allowlisted,
    non-path-parameterized, method-bearing route: that `get_current_user` sits in the route's
    mount-level dependency list, and that a request without a token actually gets a 401.

    The first is what fails if `dependencies=[Depends(get_current_user)]` is dropped from the
    `main.py` mounting loop — some handlers (e.g. `GET /users/me`) also depend on
    `get_current_user` for their own data needs, which would otherwise mask that loss and leave
    the second assertion passing for the wrong reason. The second is what fails if a route stops
    enforcing auth at the handler level while the mount-level dependency stays in place.

    Not covered by either: routes with a `{param}` in their path (skipped — need a real id,
    covered by their own feature tests), and any future route with no HTTP methods at all (e.g.
    a `Mount`), since there is nothing to iterate in that case.
    """
    assert mount_requires_auth, f"{method} {path} is mounted without get_current_user as a dependency"

    response = await client.request(method, path)

    assert response.status_code == 401, f"{method} {path} answered {response.status_code} without a token"


async def test_users_me_returns_the_authenticated_user(auth_client: AsyncClient) -> None:
    response = await auth_client.get("/v1/users/me")

    assert response.status_code == 200
    assert response.json()["email"] == "fixture@example.com"


async def test_a_malformed_token_is_rejected(client: AsyncClient) -> None:
    client.headers["Authorization"] = "Bearer not.a.token"

    response = await client.get("/v1/users/me")

    assert response.status_code == 401


async def test_a_missing_bearer_scheme_is_rejected(client: AsyncClient) -> None:
    client.headers["Authorization"] = "some-token-without-a-scheme"

    response = await client.get("/v1/users/me")

    assert response.status_code == 401
