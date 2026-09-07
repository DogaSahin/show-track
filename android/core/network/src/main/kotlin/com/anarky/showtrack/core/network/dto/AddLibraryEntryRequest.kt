package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /v1/library`. The server resolves `(source, external_id)` through the provider, creates
 * the media row if needed, and returns the entry — 201 the first time, 200 if already tracked.
 * Both are 2xx, so Retrofit returns normally for both and the caller does not have to care.
 */
@Serializable
data class AddLibraryEntryRequest(
    val source: String,
    @SerialName("external_id") val externalId: String,
)
