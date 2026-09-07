plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // SearchEntryHiltTest (task 9c.0) drives SearchScreen()'s stringResource() calls through a
        // real Compose test rule on the JVM, which needs this module's own res/values/strings.xml
        // to resolve — same requirement as :feature:library's LibraryScreenTest.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    // The route type this module registers a destination for. :core:navigation only — naming
    // another feature's module here is what ModuleRules fails the build over (rule 1).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))

    // SearchEntryHiltTest drives the stateful searchEntry() through a real Compose test rule on
    // the JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties — the same setup :feature:library uses.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // TestNavHostController, so SearchEntryHiltTest can drive searchEntry() inside a real graph
    // rather than the stateless SearchScreen() overload alone.
    testImplementation(libs.androidx.navigation.testing)

    // Hilt's test harness — composing a hiltViewModel()-backed screen in a JVM test needs it, the
    // same three lines :feature:library's build file carries.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
