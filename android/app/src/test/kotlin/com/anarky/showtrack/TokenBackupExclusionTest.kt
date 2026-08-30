package com.anarky.showtrack

import com.anarky.showtrack.core.network.auth.TOKEN_DATASTORE_NAME
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The token DataStore must never reach Auto Backup or a device transfer: its AES-GCM key lives in
 * the Android Keystore and does not travel, so a restored file is a permanently undecryptable
 * credential-shaped blob.
 *
 * The exclusion is a path string in `res/xml` and the file name is a Kotlin constant in another
 * module. Nothing but this test connects the two — rename the constant and the exclusion silently
 * stops matching anything, with no error anywhere and no visible symptom until someone restores a
 * backup. That is precisely the kind of coupling worth one test.
 *
 * Both files, not one: `full-backup-content` is what API 30 and below read and
 * `data-extraction-rules` is what API 31+ read, and minSdk is 29, so both are live.
 */
class TokenBackupExclusionTest {
    private val expectedPath = "datastore/$TOKEN_DATASTORE_NAME.preferences_pb"

    @Test
    fun `pre-31 backup rules exclude the token store`() {
        val rules = readRules("backup_rules.xml")
        assertTrue(
            "backup_rules.xml must exclude $expectedPath",
            rules.contains(excludeElement()),
        )
    }

    @Test
    fun `api-31 rules exclude the token store from both cloud backup and device transfer`() {
        val rules = readRules("data_extraction_rules.xml")
        // Two occurrences: <cloud-backup> and <device-transfer> are configured independently and
        // one does not imply the other.
        val occurrences = rules.split(excludeElement()).size - 1
        assertTrue(
            "data_extraction_rules.xml must exclude $expectedPath from BOTH cloud-backup and " +
                "device-transfer; found $occurrences exclusion(s)",
            occurrences == 2,
        )
    }

    private fun excludeElement() = """<exclude domain="file" path="$expectedPath" />"""

    // The unit-test task's working directory is the module directory, so this reaches the real
    // resource rather than a copy that could drift from it.
    private fun readRules(fileName: String) = File("src/main/res/xml/$fileName").readText()
}
