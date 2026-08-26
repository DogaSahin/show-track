plugins {
    id("showtrack.android.library")
    id("showtrack.android.hilt")
    // Module-local rather than a seventh convention plugin: kotlinx.serialization is a
    // compiler plugin only :core:network needs. Everything downstream consumes domain models
    // from :core:model, which is deliberately free of any wire annotations.
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // A Gradle property, not local.properties: `-Pshowtrack.apiBaseUrl=...` or a line in
        // gradle.properties overrides it without touching a committed file. The default is the
        // emulator's alias for the host loopback, which is where `docker compose up` puts the
        // backend. Hosting for a real deployment is still open (design doc §11), so there is no
        // production URL to bake in yet.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"" + providers.gradleProperty("showtrack.apiBaseUrl").getOrElse("http://10.0.2.2:8000/") + "\"",
        )
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.okhttp.mockwebserver)
    // Dagger's own @Component processor, for unit tests only. It is what lets NetworkModuleTest
    // assemble the REAL module into a graph instead of hand-copying what the module provides —
    // the qualifier on a @Provides parameter is invisible to a direct function call, so without a
    // component nothing can catch AuthApi being moved onto the authenticated client.
    // hilt-android-compiler bundles dagger-compiler, so no extra artifact is needed.
    kspTest(libs.hilt.compiler)

    // Instrumentation only: the Android Keystore has no off-device implementation, so whether it
    // accepts this KeyGenParameterSpec is only answerable on a device. espresso-core is here for
    // the AndroidJUnitRunner it brings with it, not for Espresso itself.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
