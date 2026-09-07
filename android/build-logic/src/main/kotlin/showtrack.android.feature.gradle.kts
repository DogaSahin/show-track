import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("showtrack.android.library")
    id("showtrack.android.compose")
    // Every feature module owns at least one @HiltViewModel, so KSP and the Hilt compiler belong
    // to the feature harness rather than being re-declared nine times. Applied, not hand-wired:
    // showtrack.android.hilt is the single place that knows AGP 9 forbids KGP but permits KSP.
    id("showtrack.android.hilt")
}

// The module-dependency rules are NOT applied here. They live in showtrack.android.library, which
// this plugin applies: a feature module that reached for library + compose directly — exactly what
// :core:designsystem looks like — would otherwise slip past the guard entirely.

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Version-catalog artifacts only — deliberately no `project(":core:...")` here, matching every
// other convention plugin in this build. Two reasons, and the first was measured:
//
// 1. A convention plugin that names a project path hardcodes THIS build's module layout into
//    build-logic, which is an included build that knows nothing about it. DependencyRuleTestKitTest
//    stands up a synthetic project containing only :feature:a, :feature:b, :core:network and
//    :core:data; an `implementation(project(":core:designsystem"))` here failed three of those
//    tests outright with "Project with path ':core:designsystem' could not be found" — the plugin
//    could no longer be applied anywhere but in this one repository.
// 2. It keeps a feature's own dependencies readable in its own build file. That is the whole point
//    of the acceptance check on :feature:library — you can see what it reaches for, and see that
//    Retrofit and Room are not among them.
dependencies {
    // Every feature now registers its own destination through a `NavGraphBuilder.xEntry()`
    // extension (Task 9), so `NavGraphBuilder` and `composable<T>` are part of the feature
    // harness rather than a per-module dependency repeated nine times. Declared explicitly and
    // not leaned on transitively: hilt-navigation-compose happens to drag navigation-compose in
    // today, but that is its implementation detail and a patch release could drop it.
    add("implementation", libs.findLibrary("androidx-navigation-compose").get())

    // hiltViewModel(), which is how a @HiltViewModel is obtained inside a composable. It scopes
    // the ViewModel to the enclosing NavBackStackEntry when there is one, so a screen popped off
    // the back stack clears its ViewModel instead of leaking it to the activity's store.
    add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
}
