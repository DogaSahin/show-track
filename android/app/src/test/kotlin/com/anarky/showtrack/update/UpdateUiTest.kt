package com.anarky.showtrack.update

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.R
import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The stateless banner and blocking screen, driven directly — no ViewModel, no Hilt graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int) = context.getString(id)

    @Test
    fun `the banner names the version and offers Install and Later`() {
        composeRule.setContent {
            UpdateBanner(release = RELEASE, progress = null, failed = false, onInstall = {}, onLater = {})
        }

        composeRule.onNodeWithText(context.getString(R.string.update_available_title, "1.1")).assertExists()
        composeRule.onNodeWithText("Faster sync").assertExists()
        composeRule.onNodeWithText(string(R.string.update_install)).assertExists()
        composeRule.onNodeWithText(string(R.string.update_later)).assertExists()
    }

    @Test
    fun `Later calls onLater`() {
        var later = 0
        composeRule.setContent {
            UpdateBanner(release = RELEASE, progress = null, failed = false, onInstall = {}, onLater = { later++ })
        }

        composeRule.onNodeWithText(string(R.string.update_later)).performClick()

        assertEquals(1, later)
    }

    @Test
    fun `a running download hides the buttons`() {
        composeRule.setContent {
            UpdateBanner(release = RELEASE, progress = 0.4f, failed = false, onInstall = {}, onLater = {})
        }

        composeRule.onNodeWithText(string(R.string.update_install)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.update_later)).assertDoesNotExist()
    }

    @Test
    fun `a failed download offers Retry, which installs`() {
        var installs = 0
        composeRule.setContent {
            UpdateBanner(release = RELEASE, progress = null, failed = true, onInstall = { installs++ }, onLater = {})
        }

        composeRule.onNodeWithText(string(R.string.update_failed)).assertExists()
        composeRule.onNodeWithText(string(R.string.update_retry)).performClick()

        assertEquals(1, installs)
    }

    @Test
    fun `the required screen offers Install and no Later`() {
        var installs = 0
        composeRule.setContent {
            UpdateRequiredScreen(release = RELEASE, progress = null, failed = false, onInstall = { installs++ })
        }

        composeRule.onNodeWithText(string(R.string.update_required_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.update_later)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.update_install)).performClick()

        assertEquals(1, installs)
    }

    @Test
    fun `signed out, nothing is checked and no prompt covers the screen`() {
        var checks = 0
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Required(RELEASE)),
                signedIn = false,
                actions = UpdateActions(onCheck = { checks++ }, onInstall = {}, onLater = {}),
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(CONTENT).assertExists()
        composeRule.onNodeWithText(string(R.string.update_required_title)).assertDoesNotExist()
        assertEquals(0, checks)
    }

    @Test
    fun `signed in, the check runs and an available update shows the banner over the app`() {
        var checks = 0
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Available(RELEASE)),
                signedIn = true,
                actions = UpdateActions(onCheck = { checks++ }, onInstall = {}, onLater = {}),
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(context.getString(R.string.update_available_title, "1.1")).assertExists()
        composeRule.onNodeWithText(CONTENT).assertExists()
        assertEquals(1, checks)
    }

    @Test
    fun `a required update blocks the app, including for accessibility`() {
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Required(RELEASE)),
                signedIn = true,
                actions = UpdateActions(onCheck = {}, onInstall = {}, onLater = {}),
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(string(R.string.update_required_title)).assertExists()
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
    }

    private companion object {
        const val CONTENT = "the app"

        val RELEASE =
            AppRelease(
                versionCode = 2,
                versionName = "1.1",
                minVersionCode = 2,
                notes = "Faster sync",
                sizeBytes = 1024,
                sha256 = "ab".repeat(32),
            )
    }
}
