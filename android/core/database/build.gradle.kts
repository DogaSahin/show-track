plugins {
    id("showtrack.android.library")
    // Brings KSP and the Hilt Gradle plugin — reused here for the Room annotation processor too,
    // rather than re-applying `com.google.devtools.ksp` a second time from this module.
    id("showtrack.android.hilt")
}

android {
    testOptions {
        // Robolectric needs this to load the Android resources/manifest it shadows, even though
        // LibraryDaoTest itself touches no resources — it's Robolectric's own prerequisite, not
        // a Room one.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    // Enables `suspend fun` / `Flow<T>` return types on @Query and @Insert methods. Without it,
    // Room only understands the callback- and Cursor-shaped API surface.
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // LibraryDaoTest runs on Robolectric: a real SQLite on the host JVM, not a fake, so this is a
    // JVM unit test rather than an androidTest — see the class doc for why (and why sdk=35, in
    // src/test/resources/robolectric.properties). kotlinx-coroutines-test and turbine are already
    // testImplementation from showtrack.android.library; only what's genuinely module-specific
    // goes here.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
