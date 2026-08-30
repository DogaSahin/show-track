package showtrack.buildlogic

import java.io.File

/**
 * The TestKit fixture builds a real Android project, so it needs the two things every Android build
 * needs and a synthetic temp directory does not have: the shared version catalog the convention
 * plugins read, and an SDK location.
 */
internal object TestFixtures {

    val versionCatalog: File =
        File(requireNotNull(System.getProperty("showtrack.versionCatalog")) {
            "showtrack.versionCatalog system property is not set; see build-logic/build.gradle.kts"
        })

    /** Shared with the outer build so TestKit reuses its downloads instead of starting cold. */
    val gradleUserHome: File =
        File(requireNotNull(System.getProperty("showtrack.gradleUserHome")) {
            "showtrack.gradleUserHome system property is not set; see build-logic/build.gradle.kts"
        })

    private val androidSdk: String? = System.getProperty("showtrack.androidSdk")?.takeIf { it.isNotBlank() }

    fun writeLocalProperties(projectDir: File) {
        androidSdk?.let {
            projectDir.resolve("local.properties").writeText("sdk.dir=${File(it).invariantSeparatorsPath}\n")
        }
    }

    /**
     * The scaffold is a SEPARATE Gradle build, and `gradle.properties` is per-build — nothing in
     * the outer project reaches it. So every property the convention plugins depend on has to be
     * written here too, exactly like `local.properties` above.
     *
     * `android.disallowKotlinSourceSets=false` is the one that matters: KSP registers its
     * generated-source directory through the `kotlin.sourceSets` DSL, which AGP 9 rejects by
     * default with "Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in
     * Kotlin". It became load-bearing here the moment `showtrack.android.feature` started applying
     * `showtrack.android.hilt` (and with it KSP) — before that, no scaffolded module ran an
     * annotation processor and the omission was invisible.
     *
     * Worth noting how it hid: the two dependency-rule tests failed on the SAME scaffold defect but
     * never showed it, because `ModuleRules` fires from `dependencies.configureEach` and aborts
     * configuration before AGP gets as far as validating source sets. Only the ktlint test, which
     * declares no forbidden dependency, ever reached the real error.
     */
    fun writeGradleProperties(projectDir: File) {
        projectDir.resolve("gradle.properties").writeText("android.disallowKotlinSourceSets=false\n")
    }
}
