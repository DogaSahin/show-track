package com.anarky.showtrack.core.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The store over the real Preferences DataStore — the repository's own tests fake it, so this is
 * what proves a snooze is stored per version and a newer one replaces it.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateSnoozeStoreTest {
    @Test
    fun `a snooze answers only for its own version, and a newer snooze replaces it`() =
        runBlocking {
            // Through the app context, as production builds it: DataStore over a bare temp file
            // cannot rename its write file on Windows. One method, because the delegate caches its
            // DataStore against the first context it sees.
            val store = DataStoreUpdateSnoozeStore(ApplicationProvider.getApplicationContext<Context>())
            val until = Instant.parse("2026-10-25T12:00:00Z")

            store.snooze(versionCode = 4, until = until)

            assertEquals(until, store.snoozedUntil(4))
            assertNull(store.snoozedUntil(5))

            val later = until.plusSeconds(3600)
            store.snooze(versionCode = 5, until = later)

            assertEquals(later, store.snoozedUntil(5))
            assertNull(store.snoozedUntil(4))
        }
}
