plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // GroupsScreenTest drives GroupsScreen()'s stringResource() calls through a real Compose
        // test rule on the JVM, which needs this module's own res/values/strings.xml to resolve —
        // same requirement as :feature:favorites' FavoritesScreenTest / :feature:library's
        // LibraryScreenTest.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // LoadingState, ErrorState, EmptyState, StaleDataBanner — decision C-T: a feature never
    // re-implements a design-system component.
    implementation(project(":core:designsystem"))

    // The route type this module registers a destination for (GroupsRoute, GroupDetailRoute).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))

    // GroupsScreenTest drives the stateless GroupsScreen() through a real Compose test rule on
    // the JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties — the same setup :feature:favorites/:feature:library use.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // TestNavHostController, so GroupsEntryHiltTest can drive groupsEntry() inside a real graph
    // rather than the stateless GroupsScreen() overload alone (task 9c.1, closing the E-L gap this
    // module was excluded from in task 9c.0 because groupsEntry() took no onNavigate then).
    testImplementation(libs.androidx.navigation.testing)

    // Hilt's test harness — composing a hiltViewModel()-backed screen in a JVM test needs it, the
    // same three lines :feature:favorites'/:feature:library's build files carry.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
