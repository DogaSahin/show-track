// Every plugin this build applies comes from the `build-logic` included build, so nothing is
// declared here: listing AGP with `apply false` as well would put it on two classpaths at once.

// The convention plugins — and with them the enforced module-dependency rules — live in that
// included build, whose tests `./gradlew testDebugUnitTest` would otherwise never reach. Gradle
// matches a task name against the root project as well as the subprojects, so a root lifecycle
// task of that name pulls them into the gate that is already documented and already run in CI.
// A guard whose tests the gate does not run is a guard that can rot without anyone noticing.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the build-logic convention-plugin tests alongside the Android unit tests."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
