import json
from collections.abc import Callable, Generator
from pathlib import Path

import pytest
from httpx import AsyncClient

from app.config import Settings, get_settings
from main import app

APK_BYTES = b"PK\x03\x04 fake apk payload " * 50
APK_MIME = "application/vnd.android.package-archive"


def _manifest(**overrides: object) -> dict[str, object]:
    base: dict[str, object] = {
        "version_code": 4,
        "version_name": "1.2.0",
        "min_version_code": 2,
        "notes": "Faster library loading.",
        "sha256": "ab" * 32,
        "apk": "showtrack-4.apk",
    }
    base.update(overrides)
    return base


@pytest.fixture
def releases_dir(tmp_path: Path) -> Generator[Path, None, None]:
    """Points the releases_dir setting at tmp_path for the duration of one test."""
    real = get_settings()
    app.dependency_overrides[get_settings] = lambda: real.model_copy(update={"releases_dir": tmp_path})
    yield tmp_path
    app.dependency_overrides.pop(get_settings, None)


@pytest.fixture
def publish(releases_dir: Path) -> Callable[..., None]:
    def _publish(manifest: dict[str, object] | str | None = None, *, with_apk: bool = True) -> None:
        if with_apk:
            (releases_dir / "showtrack-4.apk").write_bytes(APK_BYTES)
        body = _manifest() if manifest is None else manifest
        text = body if isinstance(body, str) else json.dumps(body)
        (releases_dir / "release.json").write_text(text, encoding="utf-8")

    return _publish


async def test_published_release_is_returned(auth_client: AsyncClient, publish: Callable[..., None]) -> None:
    publish()

    response = await auth_client.get("/v1/app/release")

    assert response.status_code == 200
    assert response.json() == {
        "version_code": 4,
        "version_name": "1.2.0",
        "min_version_code": 2,
        "notes": "Faster library loading.",
        "size_bytes": len(APK_BYTES),
        "sha256": "ab" * 32,
    }


async def test_no_release_json_returns_204(auth_client: AsyncClient, releases_dir: Path) -> None:
    response = await auth_client.get("/v1/app/release")

    assert response.status_code == 204
    assert response.content == b""


_BROKEN: list[tuple[str, dict[str, object] | str, bool]] = [
    ("invalid-json", "{not json", True),
    ("missing-sha256", {k: v for k, v in _manifest().items() if k != "sha256"}, True),
    ("apk-parent-traversal", _manifest(apk="../evil.apk"), True),
    ("apk-subdirectory", _manifest(apk="sub/a.apk"), True),
    ("apk-absent", _manifest(), False),
    ("min-above-version", _manifest(min_version_code=5), True),
]


@pytest.mark.parametrize(("manifest", "with_apk"), [p[1:] for p in _BROKEN], ids=[p[0] for p in _BROKEN])
async def test_broken_release_returns_204(
    auth_client: AsyncClient,
    publish: Callable[..., None],
    manifest: dict[str, object] | str,
    with_apk: bool,
) -> None:
    publish(manifest, with_apk=with_apk)

    response = await auth_client.get("/v1/app/release")

    assert response.status_code == 204


async def test_apk_download_streams_the_file(auth_client: AsyncClient, publish: Callable[..., None]) -> None:
    publish()

    response = await auth_client.get("/v1/app/release/apk")

    assert response.status_code == 200
    assert response.content == APK_BYTES
    assert response.headers["content-type"] == APK_MIME
    assert int(response.headers["content-length"]) == len(APK_BYTES)


async def test_apk_download_without_release_is_404(auth_client: AsyncClient, releases_dir: Path) -> None:
    response = await auth_client.get("/v1/app/release/apk")

    assert response.status_code == 404


async def test_release_endpoints_require_auth(client: AsyncClient, publish: Callable[..., None]) -> None:
    publish()

    assert (await client.get("/v1/app/release")).status_code == 401
    assert (await client.get("/v1/app/release/apk")).status_code == 401


def test_settings_default_releases_dir() -> None:
    assert Settings.model_fields["releases_dir"].default == Path("/app/releases")
