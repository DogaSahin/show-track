package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /v1/app/release` (backend's `AppRelease`). */
@Serializable
data class AppReleaseDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("min_version_code") val minVersionCode: Int,
    val notes: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)
