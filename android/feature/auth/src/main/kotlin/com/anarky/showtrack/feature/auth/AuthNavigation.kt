package com.anarky.showtrack.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AuthRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * No `onNavigate` parameter: this screen has nowhere to go yet. Handing every entry a navigation
 * callback "for symmetry" would be a lie about what the screen can do, and the day it gains a
 * destination the compiler asks for the parameter at the call site — `FeedScreen`'s deliberate
 * lack of a default value is the same argument one layer down.
 */
fun NavGraphBuilder.authEntry() {
    composable<AuthRoute> {
        AuthScreen()
    }
}
