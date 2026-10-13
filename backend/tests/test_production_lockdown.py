"""Production hides the API docs and the debug routes. No DB is touched: the second app has
none of conftest's dependency overrides, and these paths answer before any query.
"""

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from main import create_app
from tests.factories import valid_settings


def _client(app) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://test")


async def test_production_serves_no_docs_and_no_debug_routes() -> None:
    async with _client(create_app(valid_settings(environment="production"))) as client:
        for path in ("/docs", "/redoc", "/openapi.json"):
            assert (await client.get(path)).status_code == 404, path
        assert (await client.post("/v1/debug/sync")).status_code == 404


async def test_local_keeps_docs_and_the_debug_route() -> None:
    async with _client(create_app(valid_settings(environment="local"))) as client:
        assert (await client.get("/openapi.json")).status_code == 200
        assert (await client.post("/v1/debug/sync")).status_code == 401  # mounted, auth required


def test_an_unknown_environment_fails_at_startup() -> None:
    with pytest.raises(ValidationError):
        valid_settings(environment="prod")
