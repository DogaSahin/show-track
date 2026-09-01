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
}
