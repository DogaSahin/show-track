package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.network.dto.AppReleaseDto

internal fun AppReleaseDto.toDomain(): AppRelease =
    AppRelease(
        versionCode = versionCode,
        versionName = versionName,
        minVersionCode = minVersionCode,
        notes = notes,
        sizeBytes = sizeBytes,
        sha256 = sha256.lowercase(),
    )
