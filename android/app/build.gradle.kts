plugins {
    id("showtrack.android.application")
    id("showtrack.android.compose")
    // :app is where @HiltAndroidApp lives, so this is the module whose KSP run assembles the
    // singleton component out of every @InstallIn module on the runtime classpath.
    id("showtrack.android.hilt")
}

android {
    namespace = "com.anarky.showtrack"

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

    // Feature modules are pulled in here and nowhere else — that is what makes rule 1 (features
    // never depend on each other) possible at all. Task 9 adds the remaining eight along with the
    // NavHost that stitches them together.
    implementation(project(":feature:library"))

    // Coil's singleton loader is configured in ShowTrackApplication, so the artifacts that
    // :core:designsystem uses to RENDER images are also needed here to CONFIGURE them.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
