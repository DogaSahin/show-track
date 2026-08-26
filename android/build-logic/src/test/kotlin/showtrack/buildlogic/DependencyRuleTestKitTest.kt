package showtrack.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives a real Gradle build into each violation. The unit tests in [ModuleRulesTest] prove the
 * rules are *right*; these prove they are *reached*. A guard nobody has watched fail is
 * indistinguishable from one that is never invoked — comment out both `error(it)` calls in
 * showtrack.android.library and the four rule tests here must go red while all 13 unit tests stay
 * green. The fifth test guards a different silent failure: ktlint linting no Kotlin source at all.
 */
class DependencyRuleTestKitTest {

    private val modules = listOf(":feature:a", ":feature:b", ":core:network", ":core:data")

    private fun scaffold(projectDir: File) {
        val catalog = TestFixtures.versionCatalog.invariantSeparatorsPath
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") { from(files("$catalog")) }
                }
            }
            rootProject.name = "ruletest"
            include(${modules.joinToString { "\"$it\"" }})
            """.trimIndent(),
        )
        TestFixtures.writeLocalProperties(projectDir)
        modules.forEach { path -> module(projectDir, path).resolve("build.gradle.kts").writeText("") }
    }

    private fun module(projectDir: File, path: String): File =
        projectDir.resolve(path.removePrefix(":").replace(':', '/')).apply { mkdirs() }

    private fun buildAndFail(projectDir: File, task: String): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(TestFixtures.gradleUserHome)
            .withPluginClasspath()
            .withArguments(task)
            .buildAndFail()
            .output

    @Test
    fun `a feature depending on another feature fails the build and names both modules`(
        @TempDir projectDir: File,
    ) {
        scaffold(projectDir)
        module(projectDir, ":feature:a").resolve("build.gradle.kts").writeText(
            """
            plugins { id("showtrack.android.feature") }
            dependencies { implementation(project(":feature:b")) }
            """.trimIndent(),
        )
        val output = buildAndFail(projectDir, ":feature:a:help")
        assertTrue(output.contains(":feature:a"), "message must name the consumer")
        assertTrue(output.contains(":feature:b"), "message must name the dependency")
    }

    @Test
    fun `a feature depending on core network fails the build`(@TempDir projectDir: File) {
        scaffold(projectDir)
        module(projectDir, ":feature:a").resolve("build.gradle.kts").writeText(
            """
            plugins { id("showtrack.android.feature") }
            dependencies { implementation(project(":core:network")) }
            """.trimIndent(),
        )
        assertTrue(buildAndFail(projectDir, ":feature:a:help").contains(":core:network"))
    }

    @Test
    fun `applying library and compose by hand does not opt out of the rules`(@TempDir projectDir: File) {
        scaffold(projectDir)
        // Character-for-character what :core:designsystem declares, which is the copy-paste a new
        // feature module is most likely to start from.
        module(projectDir, ":feature:a").resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("showtrack.android.library")
                id("showtrack.android.compose")
            }
            dependencies { implementation(project(":core:network")) }
            """.trimIndent(),
        )
        assertTrue(buildAndFail(projectDir, ":feature:a:help").contains(":core:network"))
    }

    @Test
    fun `a core module re-exporting core network on api fails the build`(@TempDir projectDir: File) {
        scaffold(projectDir)
        module(projectDir, ":core:data").resolve("build.gradle.kts").writeText(
            """
            plugins { id("showtrack.android.library") }
            dependencies { api(project(":core:network")) }
            """.trimIndent(),
        )
        val output = buildAndFail(projectDir, ":core:data:help")
        assertTrue(output.contains(":core:data"), "message must name the re-exporting module")
        assertTrue(output.contains(":core:network"), "message must name the leaked module")
    }

    @Test
    fun `ktlintCheck fails on a malformed Kotlin source file`(@TempDir projectDir: File) {
        scaffold(projectDir)
        val a = module(projectDir, ":feature:a")
        a.resolve("build.gradle.kts").writeText("""plugins { id("showtrack.android.feature") }""")
        // ktlint-gradle registers no source-set task on AGP 9, so `ktlintCheck` silently linted
        // build scripts only. This is the regression test for that: a formatting error in a .kt
        // file has to fail the gate, not merely be reported by a task that never runs.
        a.resolve("src/main/kotlin").apply { mkdirs() }.resolve("Malformed.kt").writeText(
            "package a\n\nfun  bad( x : Int ){\n        println(x)\n}",
        )
        assertTrue(buildAndFail(projectDir, ":feature:a:ktlintCheck").contains("Malformed.kt"))
    }
}
