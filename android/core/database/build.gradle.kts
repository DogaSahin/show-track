plugins {
    id("showtrack.android.library")
    // Brings KSP and the Hilt Gradle plugin — reused here for the Room annotation processor too,
    // rather than re-applying `com.google.devtools.ksp` a second time from this module.
    id("showtrack.android.hilt")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    // Enables `suspend fun` / `Flow<T>` return types on @Query and @Insert methods. Without it,
    // Room only understands the callback- and Cursor-shaped API surface.
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Room DAO tests need a real SQLite (there is no off-device stand-in the way there is for the
    // pure-Kotlin logic elsewhere), so this is an androidTest, not a JVM unit test.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
}
