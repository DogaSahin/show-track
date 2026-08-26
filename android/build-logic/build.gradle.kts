import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "com.anarky.showtrack.buildlogic"

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
    providers.fileContents(layout.projectDirectory.dir("..").file("local.properties"))
        .asText
        .map { text ->
            text.lineSequence()
                .firstOrNull { it.startsWith("sdk.dir=") }
                ?.substringAfter("sdk.dir=")
                ?.replace("\\\\", "/")
                ?.trim()
                .orEmpty()
        }

val androidSdkLocation: Provider<String> =
    providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orElse(sdkFromLocalProperties)
        .orElse("")

val versionCatalogLocation: String = rootDir.parentFile.resolve("gradle/libs.versions.toml").absolutePath

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("showtrack.versionCatalog", versionCatalogLocation)
    systemProperty("showtrack.androidSdk", androidSdkLocation.get())
}
