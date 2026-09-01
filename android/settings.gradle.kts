pluginManagement {
    // The convention plugins live in an included build rather than buildSrc: buildSrc invalidates
    // every build script on any change to it, an included build does not.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShowTrack"

include(":app")
include(":core:model", ":core:designsystem", ":core:navigation")
include(":core:network", ":core:database", ":core:data")
include(
    ":feature:auth", ":feature:library", ":feature:detail", ":feature:discover",
    ":feature:favorites", ":feature:profile", ":feature:search", ":feature:groups", ":feature:feed",
)
