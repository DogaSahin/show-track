package com.anarky.showtrack.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EnumsTest {
    @Test
    fun `selectable statuses are every status except unknown, in order`() {
        assertEquals(
            listOf(
                UserMediaStatus.WATCHING,
                UserMediaStatus.COMPLETED,
                UserMediaStatus.DROPPED,
                UserMediaStatus.PLANNED,
                UserMediaStatus.PAUSED,
            ),
            UserMediaStatus.selectable,
        )
    }

    @Test
    fun `selectable media types exclude unknown`() {
        assertEquals(listOf(MediaType.ANIME, MediaType.TV), MediaType.selectable)
    }
}
