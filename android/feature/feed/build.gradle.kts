plugins {
    id("showtrack.android.feature")
}

dependencies {
    // Names :core:navigation's DetailRoute, never :feature:detail's module — the compile-by-
    // construction proof for architecture rule 1 (see FeedScreen.kt).
    implementation(project(":core:navigation"))
}
