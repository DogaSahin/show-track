plugins {
    id("showtrack.android.feature")
}

dependencies {
    implementation(project(":core:designsystem"))

    // LibraryRoute, and DetailRoute for the row-click destination — the route contract, never
    // :feature:detail itself (see LibraryNavigation.kt).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module ever declares. Retrofit and Room are
    // `implementation`-scoped inside :core:data, so neither appears here and neither is on this
    // module's compile classpath — architecture rule 2, visible from the outside. ModuleRules
    // fails the build if this line ever becomes :core:network or :core:database.
    implementation(project(":core:data"))
}
