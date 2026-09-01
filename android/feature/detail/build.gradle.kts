plugins {
    id("showtrack.android.feature")
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

    // DetailViewModelTest constructs a real SavedStateHandle and calls `toRoute<DetailRoute>()`
    // on it. That call builds an intermediate android.os.Bundle (RouteDecoder's
    // SavedStateHandleArgStore), which is unmocked on a bare JVM ("Method putCharSequence in
    // android.os.Bundle not mocked") — the same category of framework dependency :app's
    // NavGraphRegistrationTest and :core:database's Room DAO tests already run under Robolectric
    // for, rather than a reason to fall back to an untyped string-keyed lookup.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
