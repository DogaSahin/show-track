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
}
