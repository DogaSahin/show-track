import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// ApplicationExtension and LibraryExtension are plain interfaces; CommonExtension is generic and
// its arity moves between AGP majors, so reaching for the concrete types keeps this compiling.
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures {
            compose = true
        }
    }
}
pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> {
        buildFeatures {
            compose = true
        }
    }
}

val bom = libs.findLibrary("androidx-compose-bom").get()

dependencies {
    add("implementation", platform(bom))
    add("implementation", libs.findLibrary("androidx-compose-ui").get())
    add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
    add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    add("implementation", libs.findLibrary("androidx-compose-material3").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
    add("androidTestImplementation", platform(bom))
    add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
}
