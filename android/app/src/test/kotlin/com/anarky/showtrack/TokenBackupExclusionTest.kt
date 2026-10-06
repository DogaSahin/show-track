package com.anarky.showtrack

import com.anarky.showtrack.core.data.alerts.ALERT_SETTINGS_DATASTORE_NAME
import com.anarky.showtrack.core.data.search.RECENT_SEARCH_DATASTORE_NAME
import com.anarky.showtrack.core.network.auth.TOKEN_DATASTORE_NAME
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two DataStore files may not reach Auto Backup or a device transfer. The class is named for the
 * token store because that was the first of them; it now covers both, and any third one belongs
 * here too.
 *
 * The TOKEN store: its AES-GCM key lives in the Android Keystore and does not travel, so a
 * restored file is a permanently undecryptable credential-shaped blob.
 *
 * The EPISODE ALERTS store: the switch and which alerts fired belong to this phone (notification
 * permission does not come back with a restore), and its alert key must NOT travel: a fresh key on
 * the new phone is what stops alerts restored from the old one's WorkManager database from showing.
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
    private val alertsPath = "datastore/$ALERT_SETTINGS_DATASTORE_NAME.preferences_pb"
    private val recentSearchPath = "datastore/$RECENT_SEARCH_DATASTORE_NAME.preferences_pb"

    @Test
    fun `pre-31 backup rules exclude the token store`() {
        assertOccurrences("backup_rules.xml", tokenPath, expected = 1)
    }

    @Test
    fun `pre-31 backup rules exclude the episode alerts store`() {
        assertOccurrences("backup_rules.xml", alertsPath, expected = 1)
    }

    @Test
    fun `api-31 rules exclude the token store from both cloud backup and device transfer`() {
        // Two occurrences: <cloud-backup> and <device-transfer> are configured independently and
        // one does not imply the other.
        assertOccurrences("data_extraction_rules.xml", tokenPath, expected = 2)
    }

    @Test
    fun `api-31 rules exclude the episode alerts store from both cloud backup and device transfer`() {
        assertOccurrences("data_extraction_rules.xml", alertsPath, expected = 2)
    }

    @Test
    fun `recent searches are excluded from every kind of backup`() {
        assertOccurrences("backup_rules.xml", recentSearchPath, expected = 1)
        assertOccurrences("data_extraction_rules.xml", recentSearchPath, expected = 2)
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
