import pytest
from fastapi.routing import iter_route_contexts
from httpx import AsyncClient

from app.users.dependencies import get_current_user
from main import app

# Everything a client may reach without a token. `/v1/auth/*` is how you get one in the first
# place; `/health` is an infrastructure probe, not client contract; the rest is FastAPI's own
# documentation surface.
OPEN_PREFIXES = ("/v1/auth/", "/health", "/docs", "/redoc", "/openapi.json")


def _protected_cases() -> list[tuple[str, str, bool, bool]]:
    """Every mounted route that should demand a token, as
    (method, path, mount_requires_auth, has_path_param).

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

    `has_path_param` records whether `path` contains a `{`, but no longer causes the route to be
    dropped. Only the caller decides what to skip based on it — see
    `test_every_non_auth_route_requires_a_token`'s docstring for why the dependency assertion
    runs for these routes while the HTTP request does not.

    Routes with no HTTP methods (e.g. a `Mount`) are skipped before `.dependencies` is ever
    touched: only `APIRoute`-backed contexts carry that attribute — a plain Starlette `Route` or
    `Mount` does not, and `RouteContext.__getattr__` proxies straight through to an
    `AttributeError` for one that doesn't. `getattr(..., None) or []` is a second, independent
    guard against the same crash for any route shape that has methods but still lacks
    `.dependencies`. Measured against an appended `Mount("/v1/static", routes=[])`: before either
    guard, collection raised `AttributeError: 'Mount' object has no attribute 'dependencies'`; with
    them, the same route is silently skipped, as the docstring below now correctly claims.
    """
    found: list[tuple[str, str, bool, bool]] = []
    for route_context in iter_route_contexts(app.routes):
        path = route_context.path or ""
        methods = route_context.methods or set()
        if path.startswith(OPEN_PREFIXES):
            continue
        if not methods:  # no HTTP methods to protect (Mount, WebSocketRoute, ...)
            continue
        dependencies = getattr(route_context, "dependencies", None) or []
        mount_requires_auth = any(dep.dependency is get_current_user for dep in dependencies)
        has_path_param = "{" in path
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            found.append((method, path, mount_requires_auth, has_path_param))
    return found


def test_protected_cases_are_collected() -> None:
    """Guards `_protected_cases()` against silently going back to collecting nothing — the exact
    defect that motivated switching to `iter_route_contexts` in the first place.

    Deliberately a separate, non-parametrized test rather than assertions inside
    `_protected_cases()` itself: that function's return value is also the argument to
    `@pytest.mark.parametrize` below, which pytest evaluates at collection time. An assertion
    failing there aborts the entire session — every test in every file, not just this one —
    before anything runs. Measured: with `dependencies=[Depends(get_current_user)]` stripped from
    the `main.py` mounting loop, an in-function assertion here produced `Interrupted: 1 error
    during collection`, exit code 2, zero tests run. Moved out here, the equivalent regression is
    one named failing test among the rest.
    """
    found = _protected_cases()

    assert found, "collected zero protected routes — iter_route_contexts may have changed shape"
    assert ("GET", "/v1/users/me", True, False) in found, "the one known protected route dropped out of collection"


@pytest.mark.parametrize(("method", "path", "mount_requires_auth", "has_path_param"), _protected_cases())
async def test_every_non_auth_route_requires_a_token(
    client: AsyncClient, method: str, path: str, mount_requires_auth: bool, has_path_param: bool
) -> None:
    """Two assertions guarding two different things for every non-allowlisted route that has at
    least one HTTP method: that `get_current_user` sits in the route's mount-level dependency
    list (always), and that a request without a token actually gets a 401 (only for routes with
    no `{param}` in their path).

    The first is what fails if `dependencies=[Depends(get_current_user)]` is dropped from the
    `main.py` mounting loop — some handlers (e.g. `GET /users/me`) also depend on
    `get_current_user` for their own data needs, which would otherwise mask that loss and leave
    an HTTP-level assertion passing for the wrong reason (the handler's own dependency still
    401s on its own). This assertion is pure route-object inspection — it issues no request and
    therefore needs no real id — which is exactly why it must NOT be skipped for `{param}`
    routes: those are precisely the routes `test_a_user_cannot_delete_another_users_target`-style
    feature tests exercise with a real id and a real token, so they can never observe an
    unauthenticated request and can never catch this regression either. Before this split,
    `DELETE /v1/library/{id}` and `DELETE /v1/notifications/targets/{id}` — the two routes in the
    whole API that destroy data — had no automated guard at all against losing
    `dependencies=[Depends(get_current_user)]` from the mount.

    The second assertion is skipped for `{param}` routes because it genuinely does need a real
    id: `client.request(method, path)` against a literal `/v1/library/{id}` 404s on routing before
    auth is even checked, which would make the assertion pass or fail for reasons unrelated to
    authentication. Path-param routes still get an equivalent HTTP-level check, just a
    hand-written one per route (e.g. `test_deleting_requires_authentication` in
    `test_library_routes.py`, `test_deleting_a_target_without_a_token_is_rejected` here) — those
    exist precisely because this generic assertion cannot reach them.

    Not covered by either: any route with no HTTP methods at all (e.g. a `Mount`) —
    `_protected_cases()` skips those before either check runs.
    """
    assert mount_requires_auth, f"{method} {path} is mounted without get_current_user as a dependency"

    if has_path_param:  # needs a real id; the HTTP-level check is each route's own job instead
        return

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
