package com.anarky.showtrack.core.data.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGuardTest {
    @Test
    fun `a write that began before the session ended is dropped, one begun after runs`() =
        runTest {
            val guard = SessionGuard()
            val writes = mutableListOf<String>()
            val before = guard.current()

            guard.end { writes += "clear" }
            guard.ifStill(before) { writes += "old account" }
            guard.ifStill(guard.current()) { writes += "new account" }

            assertEquals(listOf("clear", "new account"), writes)
        }
}
