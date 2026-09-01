plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // LibraryScreenTest drives LibraryScreen()'s stringResource()/painterResource() calls
        // through a real Compose test rule on the JVM, which needs this module's own
        // res/values/strings.xml and res/drawable to resolve — same requirement as
        // :core:designsystem's StatusPresentationTest and :feature:profile's PushNotifierTest.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    // LibraryRoute, DetailRoute for the row-click destination, and SearchRoute for the header's
    // search action — the route contract, never :feature:detail or :feature:search themselves
    // (see LibraryNavigation.kt).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))

    // LibraryScreenTest drives the stateless LibraryScreen() through a real Compose test rule on
    // the JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties — the same setup :core:designsystem's
    // StatusPresentationTest uses.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
