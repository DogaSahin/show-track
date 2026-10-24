package com.anarky.showtrack.feature.auth

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header's copy rule: Log in shows the wordmark and nothing else, because its button already
 * says "Log in"; Sign up adds "New account" as the screen's only heading. Composes the stateless
 * [AuthScreen] in [HiltTestActivity] only because that is the activity this module's test manifest
 * already registers — nothing here resolves from the graph.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AuthScreenHeaderTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `log in shows the wordmark and no title`() {
        setScreen(AuthMode.LOGIN)

        composeRule.onNodeWithText(context.getString(R.string.auth_wordmark).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.auth_register_title)).assertDoesNotExist()
    }

    @Test
    fun `sign up shows the new account title`() {
        setScreen(AuthMode.REGISTER)

        composeRule.onNodeWithText(context.getString(R.string.auth_wordmark).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.auth_register_title)).assertIsDisplayed()
    }

    private fun setScreen(mode: AuthMode) {
        composeRule.setContent {
            ShowTrackTheme {
                AuthScreen(
                    state = AuthUiState.Form(mode = mode),
                    onModeChange = {},
                    onLogin = { _, _ -> },
                    onRegister = { _, _, _, _ -> },
                )
            }
        }
    }
}
