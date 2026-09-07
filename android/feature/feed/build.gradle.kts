plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // FeedScreenTest drives FeedScreen()'s stringResource() calls through a real Compose test
        // rule on the JVM, which needs this module's own res/values/strings.xml to resolve — same
        // requirement as :feature:groups' GroupsScreenTest / :feature:favorites' FavoritesScreenTest.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Names :core:navigation's DetailRoute, never :feature:detail's module — the compile-by-
    // construction proof for architecture rule 1 (see FeedScreen.kt).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))

    // LoadingState, ErrorState, EmptyState, StaleDataBanner, EndOfListTrigger — decision C-T: a
    // feature never re-implements a design-system component.
    implementation(project(":core:designsystem"))

    // FeedScreenTest drives the stateless FeedScreen() through a real Compose test rule on the
    // JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties — the same setup :feature:groups/:feature:library use.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // TestNavHostController, so FeedEntryHiltTest can drive feedEntry() inside a real graph
    // rather than the stateless FeedScreen() overload alone — GroupDetailEntryHiltTest's pattern
    // (task 9c.2), the model this task's own brief names for the entry-binding test.
    testImplementation(libs.androidx.navigation.testing)

    // Hilt's test harness — composing a hiltViewModel()-backed screen in a JVM test needs it, the
    // same three lines :feature:groups'/:feature:library's build files carry.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
