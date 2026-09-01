plugins {
    id("showtrack.android.application")
    id("showtrack.android.compose")
    // :app is where @HiltAndroidApp lives, so this is the module whose KSP run assembles the
    // singleton component out of every @InstallIn module on the runtime classpath.
    id("showtrack.android.hilt")
}

android {
    namespace = "com.anarky.showtrack"

    testOptions {
        // NavGraphRegistrationTest builds a REAL NavGraph on the JVM under Robolectric —
        // NavDestination parses its route into a deep link, which needs android.net.Uri — and
        // Robolectric cannot load the merged manifest/resources it shadows without this.
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    defaultConfig {
        applicationId = "com.anarky.showtrack"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    // :app may depend on :core:network — architecture rule 2 constrains :feature:* modules, not
    // this one. Two uses, both belonging to the composition root: the @PlainClient qualifier that
    // ShowTrackApplication hands Coil, and (from the test source set, which sees this same
    // classpath) the DataStore file name the backup-exclusion rules in res/xml have to match.
    implementation(project(":core:network"))

    // The route contract the NavHost is written against, and the data layer's re-exposed
    // `authEvents`. :core:data rather than :core:network's AuthEventBus directly — see
    // MainActivity.authEventSource for why the composition root holds itself to rule 2's story
    // even though the build does not make it.
    implementation(project(":core:navigation"))
    implementation(project(":core:data"))

    // Feature modules are pulled in here and nowhere else — that is what makes rule 1 (features
    // never depend on each other) possible at all. All nine, because :app is the only module that
    // may name more than one: it is where the nav graph is stitched.
    implementation(project(":feature:auth"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:discover"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:groups"))
    implementation(project(":feature:library"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:search"))

    // Coil's singleton loader is configured in ShowTrackApplication, so the artifacts that
    // :core:designsystem uses to RENDER images are also needed here to CONFIGURE them.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // AppRoute::class.sealedSubclasses throws KotlinReflectionNotSupportedError without this, and
    // nothing in the build pulls it in transitively. Test-only on purpose: the route set is
    // enumerated to CHECK the graph, never to build it, so kotlin-reflect never reaches :app's
    // runtime classpath.
    testImplementation(libs.kotlin.reflect)
    // A real NavGraph needs android.net.Uri (route -> deep link) and a Context, so the
    // registration test runs on the JVM under Robolectric rather than as an androidTest CI can
    // only compile. Same call :core:network, :core:data and :core:database already made.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
