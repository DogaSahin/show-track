package showtrack.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DependencyRuleTestKitTest {

    private fun buildFailsWith(dependencyPath: String, projectDir: File): String {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") { from(files("${TestFixtures.versionCatalog.invariantSeparatorsPath}")) }
                }
            }
            rootProject.name = "ruletest"
            include(":feature:a", ":feature:b", ":core:network", ":core:data")
            """.trimIndent(),
        )
        TestFixtures.writeLocalProperties(projectDir)
        listOf(":feature:b", ":core:network", ":core:data").forEach { path ->
            val dir = projectDir.resolve(path.removePrefix(":").replace(':', '/'))
            dir.mkdirs()
            dir.resolve("build.gradle.kts").writeText("")
        }
        val a = projectDir.resolve("feature/a").apply { mkdirs() }
        a.resolve("build.gradle.kts").writeText(
            """
            plugins { id("showtrack.android.feature") }
            dependencies { implementation(project("$dependencyPath")) }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":feature:a:help")
            .buildAndFail()
        return result.output
    }

    @Test
    fun `a feature depending on another feature fails the build and names both modules`(
        @TempDir projectDir: File,
    ) {
        val output = buildFailsWith(":feature:b", projectDir)
        assertTrue(output.contains(":feature:a"), "message must name the consumer")
        assertTrue(output.contains(":feature:b"), "message must name the dependency")
    }

    @Test
    fun `a feature depending on core network fails the build`(@TempDir projectDir: File) {
        assertTrue(buildFailsWith(":core:network", projectDir).contains(":core:network"))
    }
}
