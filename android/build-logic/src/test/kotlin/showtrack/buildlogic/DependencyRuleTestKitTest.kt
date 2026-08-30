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
 * green. One test guards a different silent failure: ktlint linting no Kotlin source at all. And the
 * positive control guards the scaffold itself — every other test asserts a FAILING build, so
 * without it a scaffold that cannot configure at all would keep them all green.
 * The last two cover showtrack.jvm.library's own copies of both guards: apiLeakOf is live for a
 * pure-Kotlin/JVM module (it brings java-library's `api` configuration with it), and ktlint
 * registers its source-set tasks natively there with no widening — these tests are what keeps that
 * true instead of merely believed.
 */
class DependencyRuleTestKitTest {

    private val modules = listOf(":feature:a", ":feature:b", ":core:network", ":core:data")

    // A pure-Kotlin/JVM scaffold, kept separate from `scaffold` rather than folded into it: it
    // needs no Android SDK (no `local.properties`, no `google()` requirement beyond resolving the
    // version catalog's own coordinates), which is exactly the point of showtrack.jvm.library.
    private val jvmModules = listOf(":core:model", ":core:network")

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
        TestFixtures.writeGradleProperties(projectDir)
        modules.forEach { path -> module(projectDir, path).resolve("build.gradle.kts").writeText("") }
    }

    private fun scaffoldJvm(projectDir: File) {
        val catalog = TestFixtures.versionCatalog.invariantSeparatorsPath
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") { from(files("$catalog")) }
                }
            }
            rootProject.name = "jvmruletest"
            include(${jvmModules.joinToString { "\"$it\"" }})
            """.trimIndent(),
        )
        jvmModules.forEach { path -> module(projectDir, path).resolve("build.gradle.kts").writeText("") }
    }

    private fun module(projectDir: File, path: String): File =
        projectDir.resolve(path.removePrefix(":").replace(':', '/')).apply { mkdirs() }

    private fun runner(projectDir: File, task: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(TestFixtures.gradleUserHome)
            .withPluginClasspath()
            .withArguments(task)

    private fun buildAndFail(projectDir: File, task: String): String =
        runner(projectDir, task).buildAndFail().output

    private fun build(projectDir: File, task: String): String =
        runner(projectDir, task).build().output

    /**
     * The POSITIVE control, and the only assertion in this class that can catch a broken scaffold.
     *
     * Every other test here calls `buildAndFail`, so each one passes when the build fails for the
     * RIGHT reason and equally when it fails for a wrong one — a missing SDK, an unapplied plugin,
     * a `gradle.properties` the synthetic build does not have. Measured, not theorised: with
     * `TestFixtures.writeGradleProperties` removed, the three rule tests below all still PASS
     * while the scaffold cannot configure a feature module at all. Only a run that must SUCCEED
     * distinguishes "the rule fired" from "nothing worked".
     */
    @Test
    fun `a compliant feature module configures cleanly`(@TempDir projectDir: File) {
        scaffold(projectDir)
        module(projectDir, ":feature:b").resolve("build.gradle.kts").writeText(
            """plugins { id("showtrack.android.feature") }""" + "\n",
        )
        // build(), not buildAndFail(): :feature:b declares no forbidden dependency, so applying
        // the full feature plugin — library + compose + hilt, and with hilt, KSP — has to work.
        build(projectDir, ":feature:b:help")
    }

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
        // Trailing newline matters here: without one this file itself trips
        // standard:final-newline, giving ktlintKotlinScriptCheck and ktlintMainSourceSetCheck two
        // independent failure reasons with no ordering between them — whichever task reports first
        // wins, and only one of them is guaranteed to name Malformed.kt. showtrack.android.library's
        // blanket widening happens to make both tasks see the same file set here, which is why this
        // was "accidentally" safe rather than actually well-formed — fixed anyway, so this scaffold
        // does not rely on that coincidence either (see the jvm-library test below for the case
        // where the same mistake was NOT accidentally safe).
        a.resolve("build.gradle.kts").writeText("""plugins { id("showtrack.android.feature") }""" + "\n")
        // ktlint-gradle registers no source-set task on AGP 9, so `ktlintCheck` silently linted
        // build scripts only. This is the regression test for that: a formatting error in a .kt
        // file has to fail the gate, not merely be reported by a task that never runs.
        a.resolve("src/main/kotlin").apply { mkdirs() }.resolve("Malformed.kt").writeText(
            "package a\n\nfun  bad( x : Int ){\n        println(x)\n}",
        )
        assertTrue(buildAndFail(projectDir, ":feature:a:ktlintCheck").contains("Malformed.kt"))
    }

    @Test
    fun `a jvm-library module re-exporting core network on api fails the build`(@TempDir projectDir: File) {
        scaffoldJvm(projectDir)
        module(projectDir, ":core:model").resolve("build.gradle.kts").writeText(
            """
            plugins { id("showtrack.jvm.library") }
            dependencies { api(project(":core:network")) }
            """.trimIndent(),
        )
        val output = buildAndFail(projectDir, ":core:model:help")
        assertTrue(output.contains(":core:model"), "message must name the re-exporting module")
        assertTrue(output.contains(":core:network"), "message must name the leaked module")
    }

    @Test
    fun `ktlintCheck fails on a malformed Kotlin source file in a jvm-library module`(@TempDir projectDir: File) {
        scaffoldJvm(projectDir)
        val model = module(projectDir, ":core:model")
        // Trailing newline matters, causally: without one, this file itself trips
        // standard:final-newline, so ktlintKotlinScriptCheck and ktlintMainSourceSetCheck fail
        // independently with no ordering between them. showtrack.jvm.library does NOT widen ktlint's
        // task sources (see below), so — unlike the Android scaffold above — the two tasks see
        // disjoint file sets: only ktlintMainSourceSetCheck's failure names Malformed.kt. Without
        // --continue, whichever task reports first aborts the build, so when the script-check task
        // won this race the assertion below failed even though the real check (main-source-set
        // linting a .kt file) was working correctly the whole time. Confirmed causally: with the
        // trailing newline, ktlintKotlinScriptCheck passes and ktlintMainSourceSetCheck FAILED is
        // the only failure, which is what makes the assertion deterministic rather than lucky.
        model.resolve("build.gradle.kts").writeText("""plugins { id("showtrack.jvm.library") }""" + "\n")
        // Unlike the Android case above, showtrack.jvm.library does not widen ktlint's task
        // sources — org.jetbrains.kotlin.jvm is the exact plugin id ktlint-gradle listens for, so
        // it registers ktlintMainSourceSetCheck natively. This is the regression test that keeps
        // that claim honest: it would go red if ktlint-gradle's registration hook ever stopped
        // firing, or if the plugin regressed back toward a bare kotlin.jvm alias with no ktlint
        // applied at all.
        model.resolve("src/main/kotlin").apply { mkdirs() }.resolve("Malformed.kt").writeText(
            "package a\n\nfun  bad( x : Int ){\n        println(x)\n}",
        )
        assertTrue(buildAndFail(projectDir, ":core:model:ktlintCheck").contains("Malformed.kt"))
    }
}
