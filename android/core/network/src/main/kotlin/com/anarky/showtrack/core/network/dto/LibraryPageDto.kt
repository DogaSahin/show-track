package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The cursor-paginated list envelope: `{items, next_cursor}` (architecture rule 4). */
@Serializable
data class LibraryPageDto(
    val items: List<LibraryEntryDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)
