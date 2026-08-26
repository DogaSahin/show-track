plugins {
    id("showtrack.android.library")
    id("showtrack.android.compose")
}

dependencies {
    implementation(project(":core:model"))

    // AsyncImage in MediaCard. coil-network-okhttp, not coil-network-ktor3: shares the OkHttp
    // engine Task 4 brings in for Retrofit rather than pulling in a second HTTP client.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
