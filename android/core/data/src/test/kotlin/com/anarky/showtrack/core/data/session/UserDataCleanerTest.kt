package com.anarky.showtrack.core.data.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

// Robolectric for android.util.Log, which the failure path writes to.
@RunWith(RobolectricTestRunner::class)
class UserDataCleanerTest {
    @Test
    fun `one holder failing does not leave the other holders' data behind`() =
        runTest {
            val cleared = mutableListOf<String>()
            val failing =
                object : UserData {
                    override suspend fun clearUserData(): Unit = throw IOException("disk")
                }
            val fine =
                object : UserData {
                    override suspend fun clearUserData() {
                        cleared += "fine"
                    }
                }

            UserDataCleaner(linkedSetOf(failing, fine)).clear()

            assertEquals(listOf("fine"), cleared)
        }
}
