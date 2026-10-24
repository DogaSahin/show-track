package com.anarky.showtrack.core.network.dto

/**
 * Field names of the two PATCH bodies that `:core:data` builds by hand as a `JsonObject` (a
 * tri-state score and partial updates have no data-class form). They live here, beside the DTOs,
 * so `OpenApiContractTest` can check them against the server schema like every other request:
 * both server models reject unknown fields, so a rename would otherwise 422 every edit.
 */
object LibraryPatchFields {
    const val STATUS = "status"
    const val PROGRESS = "progress"
    const val FAVORITE = "favorite"
    const val SCORE = "score"

    val ALL = listOf(STATUS, PROGRESS, FAVORITE, SCORE)
}

/** See [LibraryPatchFields]: the review-edit body built in `GroupRepositoryImpl.updateReview`. */
object ReviewPatchFields {
    const val BODY = "body"
    const val CONTAINS_SPOILERS = "contains_spoilers"

    val ALL = listOf(BODY, CONTAINS_SPOILERS)
}
