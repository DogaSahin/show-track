"""Canonical rendering of the API schema, shared by the export script and the drift test."""

import json
from pathlib import Path

from fastapi import FastAPI

SNAPSHOT_PATH = Path(__file__).resolve().parent.parent / "openapi.json"


def render(app: FastAPI) -> str:
    """Deterministic JSON text: sorted keys, 2-space indent, UTF-8 characters kept, final newline."""
    return json.dumps(app.openapi(), sort_keys=True, indent=2, ensure_ascii=False) + "\n"
