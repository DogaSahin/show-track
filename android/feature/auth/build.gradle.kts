plugins {
    id("showtrack.android.feature")
}

dependencies {
    // ErrorState and LoadingState — the AuthError copy renders through ErrorState's retry
    // affordance rather than a bare Text, matching every other screen's failure presentation.
    implementation(project(":core:designsystem"))

    // The route type this module registers a destination for. :core:navigation only — naming
    // another feature's module here is what ModuleRules fails the build over (rule 1).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit is `implementation`-scoped
    // inside :core:data, so it never appears on this module's compile classpath — architecture
    // rule 2, enforced by ModuleRules.apiLeakOf and VerifyArchitectureClasspath, not by review.
    implementation(project(":core:data"))
}
