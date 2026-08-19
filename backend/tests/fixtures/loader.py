import json
from pathlib import Path
from typing import Any

FIXTURE_ROOT = Path(__file__).parent


def load_fixture(provider: str, name: str) -> Any:
    """Read a recorded upstream response. Fixtures are committed and recorded by hand — no test
    ever touches a live API.
    """
    return json.loads((FIXTURE_ROOT / provider / f"{name}.json").read_text())
