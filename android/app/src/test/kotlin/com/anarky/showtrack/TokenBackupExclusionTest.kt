package com.anarky.showtrack

import com.anarky.showtrack.core.data.push.PUSH_DATASTORE_NAME
import com.anarky.showtrack.core.network.auth.TOKEN_DATASTORE_NAME
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Neither of the app's two secret-bearing DataStore files may reach Auto Backup or a device
 * transfer. The class is named for the token store because that was the first of them; it now
 * covers both, and any third one belongs here too.
 *
 * The TOKEN store: its AES-GCM key lives in the Android Keystore and does not travel, so a
 * restored file is a permanently undecryptable credential-shaped blob.
 *
 * The PUSH store: `endpoint` is a bearer secret in the same sense the ntfy topic is — whoever
 * holds it can post arbitrary notifications to that device — and it is the only surface that ever
 * let it leave the phone in plaintext, while `PushRegistrar` will not even put it in logcat. Its
 * `targetId` adds a second, non-secrecy reason: it names a server row belonging to the device that
 * registered it, so a restored copy makes the next `unregister()` delete the OLD device's target.
 *
 * The exclusion is a path string in `res/xml` and each file name is a Kotlin constant in another
 * module. Nothing but this test connects the two — rename a constant and the exclusion silently
 * stops matching anything, with no error anywhere and no visible symptom until someone restores a
 * backup. That is precisely the kind of coupling worth one test.
 *
 * Both files, not one: `full-backup-content` is what API 30 and below read and
 * `data-extraction-rules` is what API 31+ read, and minSdk is 29, so both are live.
 */
class TokenBackupExclusionTest {
    private val tokenPath = "datastore/$TOKEN_DATASTORE_NAME.preferences_pb"
    private val pushPath = "datastore/$PUSH_DATASTORE_NAME.preferences_pb"

    @Test
    fun `pre-31 backup rules exclude the token store`() {
        assertOccurrences("backup_rules.xml", tokenPath, expected = 1)
    }

    @Test
    fun `pre-31 backup rules exclude the push registration store`() {
        assertOccurrences("backup_rules.xml", pushPath, expected = 1)
    }

    @Test
    fun `api-31 rules exclude the token store from both cloud backup and device transfer`() {
        // Two occurrences: <cloud-backup> and <device-transfer> are configured independently and
        // one does not imply the other.
        assertOccurrences("data_extraction_rules.xml", tokenPath, expected = 2)
    }

    @Test
    fun `api-31 rules exclude the push registration store from both cloud backup and device transfer`() {
        assertOccurrences("data_extraction_rules.xml", pushPath, expected = 2)
    }

    private fun assertOccurrences(
        fileName: String,
        path: String,
        expected: Int,
    ) {
        val element = """<exclude domain="file" path="$path" />"""
        val occurrences = readRules(fileName).split(element).size - 1
        assertTrue(
            "$fileName must contain $expected exclusion(s) of $path; found $occurrences",
            occurrences == expected,
        )
    }

    // The unit-test task's working directory is the module directory, so this reaches the real
    // resource rather than a copy that could drift from it.
    private fun readRules(fileName: String) = File("src/main/res/xml/$fileName").readText()
}
