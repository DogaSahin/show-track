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
    fun `the required screen offers Install and Sign out, and no Later`() {
        var installs = 0
        var signOuts = 0
        composeRule.setContent {
            UpdateRequiredScreen(
                release = RELEASE,
                progress = null,
                failed = false,
                signOutFailed = false,
                onInstall = { installs++ },
                onSignOut = { signOuts++ },
            )
        }

        composeRule.onNodeWithText(string(R.string.update_required_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.update_later)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.update_install)).performClick()
        composeRule.onNodeWithText(string(R.string.update_sign_out)).performClick()

        assertEquals(1, installs)
        assertEquals(1, signOuts)
    }

    @Test
    fun `signed out, the session's update state ends and no prompt covers the screen`() {
        val actions = RecordingActions()
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Required(RELEASE)),
                session = UpdateSession.SignedOut,
                actions = actions,
                onSignedOut = {},
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(CONTENT).assertExists()
        composeRule.onNodeWithText(string(R.string.update_required_title)).assertDoesNotExist()
        assertEquals(0, actions.checks)
        assertEquals(1, actions.sessionsEnded)
    }

    @Test
    fun `an unknown session neither checks nor resets`() {
        val actions = RecordingActions()
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(),
                session = UpdateSession.Unknown,
                actions = actions,
                onSignedOut = {},
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.waitForIdle()
        assertEquals(0, actions.checks)
        assertEquals(0, actions.sessionsEnded)
    }

    @Test
    fun `signed in, the check runs and an available update shows the banner over the app`() {
        val actions = RecordingActions()
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Available(RELEASE)),
                session = UpdateSession.SignedIn,
                actions = actions,
                onSignedOut = {},
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(context.getString(R.string.update_available_title, "1.1")).assertExists()
        composeRule.onNodeWithText(CONTENT).assertExists()
        assertEquals(1, actions.checks)
        assertEquals(1, actions.resumes)
    }

    @Test
    fun `a required update blocks the app, including for accessibility`() {
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Required(RELEASE)),
                session = UpdateSession.SignedIn,
                actions = RecordingActions(),
                onSignedOut = {},
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(string(R.string.update_required_title)).assertExists()
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
    }

    @Test
    fun `the permission explanation is shown, and Open settings confirms it`() {
        val actions = RecordingActions()
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Available(RELEASE), explainPermission = true),
                session = UpdateSession.SignedIn,
                actions = actions,
                onSignedOut = {},
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.onNodeWithText(string(R.string.update_permission_body)).assertExists()
        composeRule.onNodeWithText(string(R.string.update_permission_open)).performClick()

        assertEquals(1, actions.permissionsConfirmed)
    }

    @Test
    fun `a finished sign out hands over to the host's navigation`() {
        var navigated = 0
        composeRule.setContent {
            UpdatePrompts(
                ui = UpdateUi(state = UpdateState.Required(RELEASE), signedOut = true),
                session = UpdateSession.SignedIn,
                actions = RecordingActions(),
                onSignedOut = { navigated++ },
            ) { modifier -> Text(CONTENT, modifier = modifier) }
        }

        composeRule.waitForIdle()
        assertEquals(1, navigated)
    }

    private class RecordingActions : UpdateActions {
        var checks = 0
        var resumes = 0
        var sessionsEnded = 0
        var permissionsConfirmed = 0

        override fun check() {
            checks++
        }

        override fun install() = Unit

        override fun later() = Unit

        override fun confirmPermission() {
            permissionsConfirmed++
        }

        override fun dismissPermission() = Unit

        override fun resumed() {
            resumes++
        }

        override fun signOut() = Unit

        override fun sessionEnded() {
            sessionsEnded++
        }
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
