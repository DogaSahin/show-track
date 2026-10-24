from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from fastapi.responses import FileResponse

from app.config import Settings, get_settings
from app.releases import service
from app.releases.schemas import AppRelease

router = APIRouter(prefix="/app", tags=["releases"])

SettingsDep = Annotated[Settings, Depends(get_settings)]

APK_MEDIA_TYPE = "application/vnd.android.package-archive"


@router.get(
    "/release",
    response_model=AppRelease,
    responses={status.HTTP_204_NO_CONTENT: {"description": "No release is published."}},
)
async def get_release(settings: SettingsDep) -> AppRelease | Response:
    release = service.load_release(settings.releases_dir)
    if release is None:
        return Response(status_code=status.HTTP_204_NO_CONTENT)
    return release.info


@router.get(
    "/release/apk",
    response_class=FileResponse,
    responses={
        status.HTTP_200_OK: {"content": {APK_MEDIA_TYPE: {}}, "description": "The published APK."},
        status.HTTP_404_NOT_FOUND: {"description": "No release is published."},
    },
)
async def get_release_apk(settings: SettingsDep) -> FileResponse:
    release = service.load_release(settings.releases_dir)
    if release is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no release published")
    return FileResponse(
        release.apk_path,
        media_type=APK_MEDIA_TYPE,
        filename=f"showtrack-{release.info.version_code}.apk",
    )
