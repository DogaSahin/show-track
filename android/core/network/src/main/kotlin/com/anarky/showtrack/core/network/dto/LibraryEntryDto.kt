package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryEntryDto(
    val id: String,
    val status: String,
    // String, NOT Double — the API sends "8.5" as a JSON string on purpose (backend decision 4-N).
    // Typing this Double both fails to parse and would reintroduce IEEE 754 drift if it did.
    val score: String?,
    val progress: Int,
    val favorite: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    val media: MediaDto,
)
