plugins {
    id("showtrack.android.feature")
}

dependencies {
    // The route type this module registers a destination for. :core:navigation only — naming
    // another feature's module here is what ModuleRules fails the build over (rule 1).
    implementation(project(":core:navigation"))
}
