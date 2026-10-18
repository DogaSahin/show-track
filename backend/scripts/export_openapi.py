"""Regenerate backend/openapi.json from the app's routes. Needs no database and no real settings.

Run from backend/:  .venv/Scripts/python scripts/export_openapi.py  (POSIX: .venv/bin/python)
"""

import os
import sys
from pathlib import Path

# Throwaway values, assigned (not setdefault) so a developer's real environment or .env can never
# change the output. Importing `main` builds Settings, which requires these; nothing connects.
os.environ["DATABASE_URL"] = "postgresql+asyncpg://export:export@localhost:5432/export"
os.environ["SECRET_KEY"] = "openapi-export-placeholder-value-not-a-secret"
os.environ["REGISTRATION_CODE"] = "openapi-export-placeholder"
os.environ["ENVIRONMENT"] = "local"

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.openapi_snapshot import SNAPSHOT_PATH, render  # noqa: E402
from main import app  # noqa: E402


def main() -> None:
    with SNAPSHOT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        f.write(render(app))
    print(f"wrote {SNAPSHOT_PATH}")


if __name__ == "__main__":
    main()
