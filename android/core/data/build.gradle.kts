plugins {
    id("showtrack.android.library")
    // Brings KSP and the Hilt Gradle plugin for `di/DataModule.kt`. Applied, not hand-wired:
    // the plugin is the single place that knows AGP 9 forbids KGP but permits KSP.
    id("showtrack.android.hilt")
}

android {
    testOptions {
        // LibraryRepositoryImplTest builds a REAL in-memory Room database on the JVM via
        // Robolectric, and Robolectric cannot load the merged resources/manifest it shadows
        // without this. Per-module, so :core:database setting it says nothing about this module.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // `api`, deliberately: LibraryRepository's signature is `Flow<List<LibraryEntry>>`, so every
    // :feature: consumer needs :core:model on its compile classpath just to name what it is
    // handed. That is the whole distinction this module draws — the wire shape (:core:network)
    // and the cache shape (:core:database) stop here, the domain shape does not.
    api(project(":core:model"))

    // `implementation`, NEVER `api`. An `api` edge here would put Retrofit and Room on the
    // compile classpath of every feature module and defeat architecture rule 2 transitively.
    //
    // TWO checks stand behind that, and it is worth knowing which one covers which, because the
    // comment that used to sit here named only the first and claimed it covered both:
    // `ModuleRules.apiLeakOf` fails the build on an `api(project(...))` edge — these two lines,
    // from this side — and it sees NOTHING ELSE, because it inspects declared PROJECT
    // dependencies. `api(libs.retrofit.core)` four lines below would have configured cleanly.
    // `VerifyArchitectureClasspath` is what covers the artifacts: it runs inside every feature
    // module's `preBuild` and fails if anything owned by :core:network or :core:database has
    // reached that module's compile classpath, by whatever route.
    implementation(project(":core:network"))
    implementation(project(":core:database"))

    // The push registration record — one id and one endpoint, which the server's DELETE needs and
    // the list endpoint deliberately will not hand back. `implementation`, so DataStore is on
    // this module's classpath and on no feature's, exactly like Retrofit and Room above.
    implementation(libs.androidx.datastore.preferences)
    // `Json` is a constructor parameter of PushRepositoryImpl (the instance NetworkModule
    // provides, so the client and the push decoder cannot drift on `ignoreUnknownKeys`), and
    // kotlinx-serialization-json is `implementation`-scoped inside :core:network — so it reaches
    // this module's RUNTIME classpath but not its COMPILE one without this line. No serialization
    // COMPILER plugin here: this module names no @Serializable type of its own.
    implementation(libs.kotlinx.serialization.json)

    // Room's runtime is `implementation` inside :core:database, so it reaches this module's
    // RUNTIME classpath but not its COMPILE one. The repository test calls
    // `Room.inMemoryDatabaseBuilder` directly, so the test source set needs it compiled against.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
