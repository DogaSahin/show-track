"""The committed OpenAPI snapshot is the contract the Android client is checked against.

Built with ENVIRONMENT=local on purpose: production drops the debug routes from the schema, so a
snapshot taken from whatever the developer's .env says would flap.
"""

from app.openapi_snapshot import SNAPSHOT_PATH, render
from main import create_app
from tests.factories import valid_settings

HINT = (
    "API schema changed: run `.venv/Scripts/python scripts/export_openapi.py` "
    "(POSIX: .venv/bin/python) and commit backend/openapi.json"
)


def test_openapi_snapshot_is_current() -> None:
    assert SNAPSHOT_PATH.is_file(), HINT
    app = create_app(valid_settings(environment="local"))
    assert render(app) == SNAPSHOT_PATH.read_text(encoding="utf-8"), HINT
