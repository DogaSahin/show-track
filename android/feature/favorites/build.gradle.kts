plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // FavoritesScreenTest drives FavoritesScreen()'s stringResource() calls through a real
        // Compose test rule on the JVM, which needs this module's own res/values/strings.xml to
        // resolve — same requirement as :feature:library's LibraryScreenTest and
        // :feature:discover's DiscoverScreenTest.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // MediaCard, EmptyState, ErrorState, LoadingState, EndOfListTrigger, CountdownBadge — decision
    // C-T: a feature never re-implements a design-system component. EndOfListTrigger in particular
    // was extracted FROM :feature:library into here by task 9b.3, specifically so this module could
    // consume it instead of copying its `remember(itemCount)`/re-entrancy trap.
    implementation(project(":core:designsystem"))

    // The route type this module registers a destination for, and DetailRoute for the row-click
    // destination — never :feature:detail itself (see FavoritesNavigation.kt).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))

    // FavoritesScreenTest drives the stateless FavoritesScreen() through a real Compose test rule
    // on the JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties — the same setup :feature:library/:feature:discover use.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
