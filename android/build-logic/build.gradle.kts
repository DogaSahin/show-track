import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    `kotlin-dsl`
    // build-logic lints ITSELF. It did not until now, and it is the worst module in the build to
    // leave unchecked: it holds both architecture rules, both TestKit suites and all six convention
    // plugins — the code that decides whether everything else is checked. MEASURED before the fix:
    // a `Bad.kt` with double spaces, spaced parameters, 8-space indent and no trailing newline gave
    // BUILD SUCCESSFUL under the documented `./gradlew ktlintCheck detekt`, because the root build's
    // lint tasks do not reach into an included build.
    //
    // No `source(...)` widening here, unlike showtrack.android.library. `kotlin-dsl` applies
    // org.jetbrains.kotlin.jvm, which is one of the exact plugin ids ktlint-gradle hooks, so it
    // registers its main/test source-set tasks natively — the same reason showtrack.jvm.library
    // needs no widening either.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

group = "com.anarky.showtrack.buildlogic"

// kotlin-dsl puts its GENERATED accessors (PluginSpecBuilders.kt and the precompiled-script
// adapters) into the MAIN Kotlin source set, so ktlint lints code nobody wrote and nobody can fix:
// before this, ktlintMainSourceSetCheck produced a 14 MB report of violations in
// build/generated-sources and not one from src/.
//
// Excluded on the TASK, and by a PatternFilterable spec — two dead ends are worth recording so
// nobody spends the afternoon on them again:
//
//   `ktlint { filter { exclude { ... } } }` — the extension's own documented filter — was measured
//   to have NO effect on ktlint-gradle 13.1.0; the generated files were still reported in full.
//
//   `setSource(source.filter { ... })` fails with a bare `java.lang.StackOverflowError`. `source`
//   is lazy, so the filter it is assigned to reads `source` again when the task resolves it, which
//   reads the filter, forever. A self-referential FileTree gives no useful message at all.
//
// The spec receives the absolute path, which is what makes it robust: matching relative paths would
// depend on where each source-tree root happens to start. "/build/" and not "build" so that
// build-logic's own directory name is not swept up with it.
tasks.withType<BaseKtLintCheckTask>().configureEach {
    exclude { it.file.invariantSeparatorsPath.contains("/build/") }
}

detekt {
    buildUponDefaultConfig = true
    // rootDir is build-logic/ here, because this is an INCLUDED BUILD with its own root. The
    // shared config lives one level up, reached the same way the version catalog below is.
    config.setFrom(rootDir.parentFile.resolve("config/detekt/detekt.yml"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    // Precompiled script plugins that `plugins { id(...) }` a third-party plugin need it on the
    // runtime classpath too, so these are implementation rather than compileOnly.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.ktlint.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
}

// The TestKit fixture spins up a real Android build in a temp directory, which needs the shared
// version catalog and an SDK location. ANDROID_HOME/ANDROID_SDK_ROOT is what CI sets; the sdk.dir
// in the (gitignored) local.properties is the fallback on a developer machine.
//
// Both are read through `providers` rather than System.getenv/File.readText so that the values
// become tracked configuration-cache inputs, and both are resolved to plain Strings here rather
// than passed as a lambda: a lambda declared in a build script captures the script object, which
// the configuration cache cannot serialize.
val sdkFromLocalProperties: Provider<String> =
    providers
        .fileContents(layout.projectDirectory.dir("..").file("local.properties"))
        .asText
        .map { text ->
            text
                .lineSequence()
                .firstOrNull { it.startsWith("sdk.dir=") }
                ?.substringAfter("sdk.dir=")
                ?.replace("\\\\", "/")
                ?.trim()
                .orEmpty()
        }

val androidSdkLocation: Provider<String> =
    providers
        .environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orElse(sdkFromLocalProperties)
        .orElse("")

val versionCatalogLocation: String = rootDir.parentFile.resolve("gradle/libs.versions.toml").absolutePath

// TestKit otherwise gets a throwaway Gradle home and re-downloads AGP and the ktlint runtime from
// cold on every machine that has never run these tests — measured at 71 minutes for one ktlint test,
// against 15s warm. Pointing it at the shared Gradle home reuses what the gate's own ktlint and
// detekt run has already fetched, which on CI is the Lint step that runs immediately before this one.
val sharedGradleUserHome: String = gradle.gradleUserHomeDir.absolutePath

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("showtrack.versionCatalog", versionCatalogLocation)
    systemProperty("showtrack.androidSdk", androidSdkLocation.get())
    systemProperty("showtrack.gradleUserHome", sharedGradleUserHome)
}
