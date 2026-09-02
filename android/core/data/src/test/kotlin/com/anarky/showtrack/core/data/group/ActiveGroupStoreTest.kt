package com.anarky.showtrack.core.data.group

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The store's own logic, on the JVM — `DataStoreTokenStoreTest`'s own shape (Robolectric, not an
 * `androidTest`, because everything here needs a file, not the Android Keystore).
 *
 * Only [the active group survives a new store instance over the same file] touches the real
 * `context.activeGroupDataStore` delegate, and deliberately just once: `DataStoreTokenStoreTest`'s
 * own KDoc names the trap — the delegate caches ONE DataStore against the first `Context` it is
 * given, while Robolectric hands out a fresh `filesDir` per test method. A second test method
 * reaching for it here would operate on a store pointed at a directory Robolectric has already torn
 * down. Every other test goes through the CONSTRUCTOR SEAM instead — a hand-built
 * `DataStore<Preferences>` over a [TemporaryFolder] file the delegate never touches.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveGroupStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun realDataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }) {
            file
        }

    /**
     * The whole point of E-D: the selection survives a cold start. A SECOND, THIRD
     * [DataStoreActiveGroupStore] built from the SAME [Context] proves the constructor wiring is
     * correct — `DataStoreTokenStoreTest`'s own "undecryptable tokens" test uses the identical
     * one-context, multiple-instances shape for the same reason: the `preferencesDataStore`
     * delegate caches ONE DataStore per `Context` and hands the SAME instance back every time, so a
     * "fresh" wrapper genuinely reads back what an earlier one wrote, over the same underlying
     * file — unlike constructing two independent `DataStore`s directly over one file, which THROWS
     * (measured: that is exactly what a first version of this test, using
     * `PreferenceDataStoreFactory.create` twice over one path, did).
     *
     * Both assertions live in ONE test method deliberately, not two: a second `@Test` reaching for
     * `context.activeGroupDataStore` would operate on a store pointed at a `filesDir` Robolectric
     * has already torn down between test methods.
     */
    @Test
    fun `the active group survives a new store instance, and a clear persists the same way`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val first = DataStoreActiveGroupStore(context)

            first.setActiveGroup("group-1")
            val second = DataStoreActiveGroupStore(context)
            assertEquals("group-1", second.activeGroupId.first())

            second.setActiveGroup(null)
            val third = DataStoreActiveGroupStore(context)
            assertNull(third.activeGroupId.first())
        }

    /**
     * A crash loop at launch over a cached group id would be the worst possible failure for this
     * feature — worse than simply forgetting which group was active, which is what this test
     * proves happens instead.
     *
     * A DIRECTORY where DataStore expects a plain file, not hand-crafted garbage bytes: a first
     * version of this test wrote raw bytes directly to the `.preferences_pb` file, on the theory
     * that malformed protobuf would throw `CorruptionException` the way `ReplaceFileCorruptionHandler`
     * exists to catch. Measured, twice, that it does not — the underlying parser (protobuf-lite's
     * `CodedInputStream`) reads a leading `0x00` byte as a benign end-of-stream marker rather than a
     * fault, and is lenient enough about a truncated/overflowing varint that eleven `0xFF` bytes
     * still decoded to an empty message with no exception at all. A directory in place of the file
     * sidesteps the parser entirely: opening it throws a plain `FileNotFoundException` (confirmed by
     * mutation below) before any protobuf parsing runs, which is exactly the failure
     * [DataStoreActiveGroupStore.activeGroupId]'s OWN `.catch { cause is IOException }` — not
     * DataStore's separate `corruptionHandler` — exists to survive, the same idiom
     * `DataStorePushRegistrationStore.read()` already uses.
     */
    @Test
    fun `a corrupt preferences file yields null rather than crashing at launch`() =
        runBlocking {
            val directory = tempFolder.newFolder("corrupt.preferences_pb")

            val store = DataStoreActiveGroupStore(realDataStore(directory))

            assertNull(store.activeGroupId.first())
        }
}
