package com.anarky.showtrack.core.data.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentSearchStoreTest {
    @Test
    fun `a new query goes on top and a repeat moves up instead of appearing twice`() {
        val list =
            RecentSearches.add(
                RecentSearches.add(RecentSearches.add(emptyList(), "frieren"), "bebop"),
                "Frieren",
            )

        assertEquals(listOf("Frieren", "bebop"), list)
    }

    @Test
    fun `only the last ten are kept`() {
        val list = (1..12).fold(emptyList<String>()) { acc, n -> RecentSearches.add(acc, "q$n") }

        assertEquals(RecentSearches.LIMIT, list.size)
        assertEquals("q12", list.first())
        assertEquals("q3", list.last())
    }

    @Test
    fun `whitespace is tidied and a blank query is not recorded`() {
        val list = RecentSearches.add(RecentSearches.add(emptyList(), "  blue \n lock "), "   ")

        assertEquals(listOf("blue lock"), list)
    }

    @Test
    fun `the store keeps what it records and clear removes it all`() =
        runBlocking {
            // Through the app context, as production builds it: DataStore over a bare temp file
            // cannot rename its write file on Windows.
            val store = DataStoreRecentSearchStore(ApplicationProvider.getApplicationContext<Context>())

            store.record("frieren")
            store.record("bebop")
            assertEquals(listOf("bebop", "frieren"), store.recent.first())

            store.clear()
            assertEquals(emptyList<String>(), store.recent.first())
        }
}
