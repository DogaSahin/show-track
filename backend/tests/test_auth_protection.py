import pytest
from fastapi.routing import iter_route_contexts
from httpx import AsyncClient

from main import app

# Everything a client may reach without a token. `/v1/auth/*` is how you get one in the first
# place; `/health` is an infrastructure probe, not client contract; the rest is FastAPI's own
# documentation surface.
OPEN_PREFIXES = ("/v1/auth/", "/health", "/docs", "/redoc", "/openapi.json")


def _protected_paths() -> list[tuple[str, str]]:
    """Every mounted route that should demand a token, as (method, path).

    `app.include_router` no longer flattens routes onto `app.routes` (fastapi 0.141.1): each
    inclusion is a lazy `_IncludedRouter` wrapper, so walking `app.routes` directly and reading
    `.path`/`.methods` finds nothing behind a prefix. `iter_route_contexts` is the same
    resolution FastAPI's own OpenAPI generator uses (`fastapi/openapi/utils.py:get_openapi`) to
    get the effective, prefix-applied path and methods for every route reachable from `app`.
    """
    found: list[tuple[str, str]] = []
    for route_context in iter_route_contexts(app.routes):
        path = route_context.path or ""
        methods = route_context.methods or set()
        if path.startswith(OPEN_PREFIXES):
            continue
        if "{" in path:  # path params need real ids; covered by their own feature tests
            continue
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            found.append((method, path))
    return found


@pytest.mark.parametrize(("method", "path"), _protected_paths())
async def test_every_non_auth_route_requires_a_token(client: AsyncClient, method: str, path: str) -> None:
    """The structural guarantee: protection comes from where a router is mounted, not from a
    decorator someone has to remember. A future router added without it fails here rather than
    shipping open.
    """
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
