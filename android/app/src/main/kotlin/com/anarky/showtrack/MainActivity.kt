package com.anarky.showtrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
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

    // Same AppViewModel instance ShowTrackNavHost resolves below: both calls sit above any
    // NavBackStackEntry scope, so hiltViewModel() answers from the Activity's ViewModelStore
    // either way — this is a second read of the existing StateFlow, not a second decision.
    //
    // `start` is a ONE-SHOT emission (AppViewModel's KDoc): it fires once at launch and the flow
    // then completes, so it is never re-evaluated and cannot be trusted as a CURRENT signal in
    // either direction. It used to be compared with `== AppStart.Library`, which reasoned about
    // only the Library→Auth direction (a runtime logout leaves it stuck on `Library`) and missed
    // the opposite one entirely: an `Auth`-started session that then logs in never flips `start`
    // to `Library`, so the tabs stayed hidden for the rest of the process — Favorites, Profile,
    // and therefore Sign out, were unreachable after the primary registration/login path. The
    // only thing `start` IS a reliable signal for is `Undecided` — the brief instant before the
    // session check resolves, which is what the empty-strip guard below still needs it for.
    // `currentBackStackEntry` is the actually-current signal: it changes the moment navigation
    // lands on or leaves `AuthRoute`, whether that's the cold-start gate, a runtime logout via
    // AuthGate, or a fresh login — so it alone decides the Auth/non-Auth half of this condition.
    val appViewModel: AppViewModel = hiltViewModel()
    val start by appViewModel.start.collectAsStateWithLifecycle()
    val showNavigationTabs = shouldShowNavigationTabs(start, currentBackStackEntry?.destination)

    NavigationSuiteScaffold(
        // An empty navigationSuiteItems block does not remove the bar: NavigationSuiteScaffold
        // (material3-adaptive-navigation-suite 1.4.0) emits its container unconditionally for
        // every NavigationSuiteType except None — verified against NavigationSuiteScaffoldKt's
        // bytecode, which has no item-count check anywhere. `if (showNavigationTabs) { forEach }`
        // alone leaves an empty NavigationBar (defaultMinSize(minHeight = 80.dp)) pinned under
        // the login screen. NavigationSuiteType.None is the library's own way to remove the
        // container entirely, so that is what's gated here instead. Signed in, this reproduces
        // NavigationSuiteScaffold's own default `layoutType` expression exactly (confirmed from
        // the same bytecode), so the adaptive rail/bar/drawer choice is unchanged from before
        // this parameter was ever passed explicitly.
        layoutType =
            if (showNavigationTabs) {
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
            } else {
                NavigationSuiteType.None
            },
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                // `stringResource` is called inside each composable slot (`icon`, `label`) rather
                // than hoisted once above `item(...)`: `navigationSuiteItems` is typed
                // `NavigationSuiteScope.() -> Unit`, not `@Composable NavigationSuiteScope.() ->
                // Unit` — it's a builder DSL that registers slots for later composition (the same
                // shape as `LazyListScope`), not itself a composable context. `item(...)` compiles
                // there because `item` is a plain function; a bare `stringResource` call at that
                // same spot does not, because `stringResource` IS `@Composable` and needs one of
                // the slots below, which are.
                item(
                    icon = {
                        Icon(
                            painterResource(destination.icon),
                            contentDescription = stringResource(destination.label),
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
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
 * Whether the bottom navigation bar should be visible, extracted out of [ShowTrackApp] so it can
 * be pinned by a plain JUnit test with no Hilt/Compose harness — `LibraryNavigation.kt`'s
 * `searchNavigation` is the model for pulling a decision like this out of a composable for
 * exactly that reason.
 *
 * **Pinned here:** tabs stay hidden while [start] is [AppStart.Undecided] (the empty-strip guard
 * a cold start needs, decision C-F) and while [currentDestination] is on [AuthRoute] — signed out
 * at any point in the session, whether that is the initial auth gate or a runtime logout. Once
 * [start] has resolved to anything other than `Undecided` AND the current destination is not
 * `AuthRoute`, tabs are shown — including right after a fresh login from an `Auth`-started
 * session, which is the case that regressed before this function existed (`start` alone stayed
 * `Auth` for the rest of the process, so tabs never appeared post-login).
 *
 * **NOT pinned here, and not fixed by this change:** in an `Auth`-started session the NavHost's
 * `startDestination` is still `AuthRoute` after login, so a tab's `onClick`
 * `popUpTo(findStartDestination().id)` targets a destination no longer on the back stack and pops
 * nothing — tabs stack rather than swap. That's a real difference from a `Library`-started
 * session and needs a device check; this function only decides bar VISIBILITY, not back-stack
 * behaviour.
 */
internal fun shouldShowNavigationTabs(
    start: AppStart,
    currentDestination: NavDestination?,
): Boolean = start != AppStart.Undecided && currentDestination?.hasRoute(AuthRoute::class) != true

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
    @param:StringRes val label: Int,
    val icon: Int,
    val route: AppRoute,
) {
    HOME(R.string.destination_home, R.drawable.ic_home, LibraryRoute),
    FAVORITES(R.string.destination_favorites, R.drawable.ic_favorite, FavoritesRoute),
    PROFILE(R.string.destination_profile, R.drawable.ic_account_box, ProfileRoute),
}
