plugins {
    id("showtrack.android.library")
    id("showtrack.android.compose")
}

android {
    testOptions {
        // Robolectric needs this to load the module's own res/values/strings.xml — StatusPresentationTest
        // exercises stringResource() calls, which resolve nothing without it. Same requirement as
        // :core:database's LibraryDaoTest, for a different reason (there it's Robolectric's own
        // manifest/resource prerequisite; here it's this module's actual resources).
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // `api`, not `implementation`: this module's public composables have always named `:core:model`
    // types in their signatures — `MediaCard(media: Media, status: UserMediaStatus?, …)`,
    // `StatusTab(status: UserMediaStatus, …)`, `StatusTabRow(selected: UserMediaStatus?, …)` — and
    // `MediaSource.displayName()` (component/MediaSourcePresentation.kt) is one more. `api` is what
    // this dependency has always actually been; `implementation` only compiled because every
    // consumer today also declares `:core:data`, which re-exports `:core:model` with `api` of its
    // own — a module depending on `:core:designsystem` alone could not have called any of them.
    // `ModuleRules.apiLeakOf` does not block this: it only forbids re-exporting
    // `:core:network`/`:core:database`, the two modules the "Room is a cache" rule cares about, not
    // `:core:model`.
    api(project(":core:model"))

    // AsyncImage in MediaCard. coil-network-okhttp, not coil-network-ktor3: shares the OkHttp
    // engine Task 4 brings in for Retrofit rather than pulling in a second HTTP client.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // StatusPresentationTest drives label() (now @Composable, reading a resource) through a real
    // Compose test rule on the JVM. Robolectric supplies the Android runtime; sdk=35 is pinned in
    // src/test/resources/robolectric.properties, copied from :core:database — Robolectric ships no
    // API 36 shadow jar.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
