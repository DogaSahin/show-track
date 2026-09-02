package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.network.dto.ReviewDto
import java.time.Instant

/**
 * Split out of `GroupMapper.kt` on purpose (not for a domain reason — `GroupActorDto.toDomain()`
 * is reused here unqualified, same package, no import needed): `GroupMapper.kt` sat at exactly
 * detekt's `TooManyFunctions` file threshold once every group-domain mapper landed there, and
 * splitting the one review-specific function out is the smaller change, mirroring the network
 * layer's own `GroupDtos.kt`/`ReviewDtos.kt` split for the identical reason.
 */
fun ReviewDto.toDomain(): Review =
    Review(
        id = id,
        author = author.toDomain(),
        mediaId = mediaId,
        body = body,
        containsSpoilers = containsSpoilers,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )
