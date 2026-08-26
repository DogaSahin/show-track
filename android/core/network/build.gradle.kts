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
}
