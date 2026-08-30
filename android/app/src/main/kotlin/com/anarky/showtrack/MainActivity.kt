package com.anarky.showtrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.ProfileRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * `@AndroidEntryPoint` is not decoration: it is what gives this activity a Hilt-backed
 * `defaultViewModelProviderFactory`, and therefore what makes `hiltViewModel()` inside its
 * content resolve a `@HiltViewModel` at all. Without it the first composable that calls it
 * fails at runtime with "Given component holder class ... does not implement interface
 * GeneratedComponentManager".
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * Field injection rather than a ViewModel: the auth gate holds no state to survive a
     * configuration change — it is a subscription to a process-wide singleton flow — so a
     * ViewModel would be a container with nothing in it.
     *
     * `AuthEventSource` comes from `:core:data`, not the `AuthEventBus` in `:core:network` it
     * delegates to. `:app` is not a `:feature:*` module so the build would permit either, but
     * "`:core:data` is the only module aware of Retrofit and Room" has to be true of the
     * composition root as well or it is not a rule, just a habit.
     */
    @Inject
    lateinit var authEventSource: AuthEventSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShowTrackTheme {
                ShowTrackApp(authEvents = authEventSource.authEvents)
            }
        }
    }
}

/**
 * The composition root's UI half. Deliberately NOT `@Preview`-annotated: it hosts screens that
 * call `hiltViewModel()`, and a preview has no `@AndroidEntryPoint` activity to resolve one from
 * — the annotation would render a permanently broken preview.
 */
@Composable
fun ShowTrackApp(authEvents: Flow<AuthEvent>) {
    val navController = rememberNavController()

    // The back stack, not a local `var`: with a NavHost in the picture the selected tab is a
    // FUNCTION of where the user is, and a separate mutableStateOf would be a second source of
    // truth that drifts the moment anything navigates without touching a tab — a row tap into
    // Detail, or the auth gate firing.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            painterResource(destination.icon),
                            contentDescription = destination.label,
                        )
                    },
                    label = { Text(destination.label) },
                    selected =
                        currentBackStackEntry?.destination?.hasRoute(destination.route::class) == true,
                    onClick = {
                        navController.navigate(destination.route) {
                            // The standard top-level-destination options. saveState/restoreState
                            // keep each tab's scroll position and back stack; launchSingleTop
                            // stops re-tapping a tab stacking duplicates of the same screen.
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            ShowTrackNavHost(
                navController = navController,
                authEvents = authEvents,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * The three top-level destinations the navigation suite offers. A subset of the nine routes on
 * purpose: Detail and Auth are pushed onto the stack rather than tabbed to, and Discover, Search,
 * Groups and Feed have no chrome yet — Phase 9 decides where they surface.
 *
 * Renamed from `AppDestinations`: with [AppDestination] now naming a row in the nav graph's
 * registration table, two types one plural apart meant two different things in the same package.
 * "Top-level destination" is also the term the Navigation docs use for a tab a bottom bar or
 * rail switches between, which is exactly what this is.
 */
enum class TopLevelDestination(
    val label: String,
    val icon: Int,
    val route: AppRoute,
) {
    HOME("Home", R.drawable.ic_home, LibraryRoute),
    FAVORITES("Favorites", R.drawable.ic_favorite, FavoritesRoute),
    PROFILE("Profile", R.drawable.ic_account_box, ProfileRoute),
}
