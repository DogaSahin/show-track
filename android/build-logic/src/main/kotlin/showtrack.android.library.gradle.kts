import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import showtrack.buildlogic.ModuleRules
import showtrack.buildlogic.VerifyArchitectureClasspath
import showtrack.buildlogic.flattenModuleCoordinates

plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Derived rather than declared per module: a namespace that cannot drift from the module path, and
// one less thing every new module has to remember.
val moduleNamespace = "com.anarky.showtrack." + path.removePrefix(":").replace(':', '.')

android {
    namespace = moduleNamespace

    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

detekt {
    // The defaults plus config/detekt/detekt.yml, rather than the yml alone: a fresh detekt release
    // then brings its new rules with it instead of silently analysing nothing.
    buildUponDefaultConfig = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = JvmTarget.JVM_21.target
}

// ktlint-gradle registers its source-set tasks only from `plugins.withId` for `kotlin`,
// `org.jetbrains.kotlin.android`, `.js` and `.multiplatform`. AGP 9 owns Kotlin itself and hard-fails
// if KGP is applied alongside it, so none of those ids is ever applied in this build and the plugin
// registers its .kts tasks and nothing else — `ktlintCheck` would lint build scripts and not one
// line of Kotlin source, while looking perfectly alive. Widening the tasks it does register reuses
// its own report-and-fail pipeline instead of reimplementing task creation against internal API.
// DependencyRuleTestKitTest drives a malformed .kt file through a real build to keep this honest.
tasks.withType<BaseKtLintCheckTask>().configureEach {
    source(
        layout.projectDirectory
            .dir("src")
            .asFileTree
            .matching { include("**/*.kt") },
    )
}

dependencies {
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())

    // Test dependencies ship here, not per module: every module gets the same harness and none of
    // them can quietly diverge from it.
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("turbine").get())
}

// Both architecture rules are enforced from the *library* convention plugin rather than from
// showtrack.android.feature, so that applying library + compose by hand cannot opt out of them.
// violationOf returns null for every non-:feature: consumer, so this costs the core modules nothing.
//
// Own-project inspection only: reading another project's state at configuration time is cross-project
// configuration, which breaks the configuration cache this build has enabled.
val consumerPath = project.path

configurations.configureEach {
    val configurationName = name
    dependencies.configureEach {
        val dependencyPath = (this as? ProjectDependency)?.path ?: return@configureEach
        ModuleRules.violationOf(consumerPath, dependencyPath)?.let { error(it) }
        ModuleRules.apiLeakOf(consumerPath, configurationName, dependencyPath)?.let { error(it) }
    }
}

// The same rule 2, asserted against the RESOLVED classpath rather than against declarations, and
// the reason both checks exist is measured: `api(libs.retrofit.core)` in :core:data configured
// cleanly past the block above — which returns early on anything that is not a ProjectDependency —
// and put com.squareup.retrofit2:retrofit on :feature:library's debugCompileClasspath. See
// VerifyArchitectureClasspath for the full argument.
//
// Registered from `androidComponents.onVariants` and not at the top level, because
// `<variant>CompileClasspath` does not exist yet when this script body runs: `configurations.named`
// on it would throw UnknownConfigurationException. onVariants runs once AGP has created them.
//
// Only for :feature: modules. The rule function returns null for every other consumer anyway, but
// registering the task at all would make a core module resolve two dependency graphs to be told
// nothing.
if (consumerPath.startsWith(":feature:")) {
    // Captured under a different name on purpose. Inside the task-configuration lambda below,
    // `consumerPath` and `configurationName` both resolve to the TASK's properties and shadow the
    // script's vals — `consumerPath.set(consumerPath)` compiles and fails at execution with
    // "Circular evaluation detected", which is a confusing way to learn about shadowing.
    val modulePath = consumerPath
    androidComponents {
        onVariants { variant ->
            val classpathName = "${variant.name}CompileClasspath"
            val verify =
                tasks.register<VerifyArchitectureClasspath>(
                    "verify${variant.name.replaceFirstChar(Char::titlecase)}Architecture",
                ) {
                    group = "verification"
                    description = "Fails if $classpathName carries a :core:network or :core:database artifact."
                    consumerPath.set(modulePath)
                    configurationName.set(classpathName)
                    // The resolution RESULT, so no upstream artifact has to be built to answer
                    // the question. `rootComponent` is the configuration-cache-safe entry point;
                    // `allComponents` is an eager callback API and would not survive it.
                    coordinates.set(
                        configurations
                            .named(classpathName)
                            .flatMap { it.incoming.resolutionResult.rootComponent }
                            .map(::flattenModuleCoordinates),
                    )
                    stamp.set(layout.buildDirectory.file("architecture/$classpathName.txt"))
                }
            // preBuild, so this rides inside assembleDebug and testDebugUnitTest — both already in
            // the documented gate and in android-ci.yml. Wiring it to `check` instead would put it
            // behind a command nothing runs.
            tasks.named("preBuild") { dependsOn(verify) }
        }
    }
}
