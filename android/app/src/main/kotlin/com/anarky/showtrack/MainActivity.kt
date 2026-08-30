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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import com.anarky.showtrack.feature.library.LibraryScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * `@AndroidEntryPoint` is not decoration: it is what gives this activity a Hilt-backed
 * `defaultViewModelProviderFactory`, and therefore what makes `hiltViewModel()` inside its
 * content resolve a `@HiltViewModel` at all. Without it the first composable that calls it
 * fails at runtime with "Given component holder class ... does not implement interface
 * GeneratedComponentManager".
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShowTrackTheme {
                ShowTrackApp()
            }
        }
    }
}

/**
 * The composition root's UI half. Deliberately NOT `@Preview`-annotated any more: it now hosts a
 * screen that calls `hiltViewModel()`, and a preview has no `@AndroidEntryPoint` activity to
 * resolve one from — the annotation would render a permanently broken preview.
 */
@Composable
fun ShowTrackApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
                            contentDescription = it.label,
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            // A single screen, not a NavHost: Task 9 owns navigation. What this proves today is
            // the end of the graph — a feature composable obtaining a @HiltViewModel whose
            // repository came from the singleton component.
            LibraryScreen(
                onEntryClick = { },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    PROFILE("Profile", R.drawable.ic_account_box),
}
