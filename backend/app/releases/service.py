import json
import logging
from dataclasses import dataclass
from pathlib import Path

from app.releases.schemas import AppRelease

logger = logging.getLogger(__name__)

MANIFEST_NAME = "release.json"


@dataclass(frozen=True)
class PublishedRelease:
    info: AppRelease
    apk_path: Path


def _is_int(value: object) -> bool:
    # bool is an int subclass; `true` is not a version code.
    return isinstance(value, int) and not isinstance(value, bool)


def load_release(releases_dir: Path) -> PublishedRelease | None:
    """Read the release the owner published into `releases_dir`, or None when there isn't a usable one.

    Never raises: a half-written or hand-edited publish must degrade to "no update available"
    rather than a 5xx. Only a bare file name is accepted for the APK, so the manifest cannot point
    the server at anything outside `releases_dir`.
    """
    manifest_path = releases_dir / MANIFEST_NAME
    if not manifest_path.is_file():
        return None

    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        logger.warning("release manifest unreadable: %s", exc)
        return None

    if not isinstance(raw, dict):
        logger.warning("release manifest is not a JSON object")
        return None

    version_code = raw.get("version_code")
    min_version_code = raw.get("min_version_code")
    version_name = raw.get("version_name")
    notes = raw.get("notes")
    sha256 = raw.get("sha256")
    apk = raw.get("apk")
    if (
        not _is_int(version_code)
        or not _is_int(min_version_code)
        or not isinstance(version_name, str)
        or not isinstance(notes, str)
        or not isinstance(sha256, str)
        or not isinstance(apk, str)
    ):
        logger.warning("release manifest has missing or mistyped fields")
        return None

    if not apk or apk in {".", ".."} or Path(apk).name != apk or "/" in apk or "\\" in apk:
        logger.warning("release manifest apk is not a bare file name: %r", apk)
        return None

    if min_version_code > version_code:
        logger.warning("release manifest min_version_code %s exceeds version_code %s", min_version_code, version_code)
        return None

    apk_path = releases_dir / apk
    try:
        size_bytes = apk_path.stat().st_size if apk_path.is_file() else None
    except OSError:
        size_bytes = None
    if size_bytes is None:
        logger.warning("release apk %r is missing", apk)
        return None

    info = AppRelease(
        version_code=version_code,
        version_name=version_name,
        min_version_code=min_version_code,
        notes=notes,
        size_bytes=size_bytes,
        sha256=sha256,
    )
    return PublishedRelease(info=info, apk_path=apk_path)
