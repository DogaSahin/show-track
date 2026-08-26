package showtrack.buildlogic

/**
 * The two architecture rules, as data. Pure so they can be unit-tested without a Gradle build:
 * a rule that has only ever been observed passing is indistinguishable from one never reached.
 */
object ModuleRules {
    private val FORBIDDEN_CORE = setOf(":core:network", ":core:database")

    fun violationOf(consumerPath: String, dependencyPath: String): String? {
        if (!consumerPath.startsWith(":feature:")) return null
        // AGP wires the test variants of a module to its own main variant as a project dependency,
        // so every module legitimately depends on itself. That edge is not a cross-module edge.
        if (consumerPath == dependencyPath) return null
        if (dependencyPath.startsWith(":feature:")) {
            return "$consumerPath depends on $dependencyPath. Feature modules must not depend on " +
                "each other — route through :core:navigation and stitch in :app."
        }
        if (dependencyPath in FORBIDDEN_CORE) {
            return "$consumerPath depends on $dependencyPath. Feature modules reach data only " +
                "through :core:data, which is what keeps Room a cache rather than the source of truth."
        }
        return null
    }

    /**
     * Rule 2 arriving from the other side. `violationOf` inspects what a feature *declares*, which
     * says nothing about what reaches it transitively: one `api(project(":core:network"))` inside
     * `:core:data` re-exports Retrofit to every feature that depends on it, and every declared
     * dependency in the build stays legal. So the export side is checked at its source.
     */
    fun apiLeakOf(producerPath: String, configurationName: String, dependencyPath: String): String? {
        if (!producerPath.startsWith(":core:")) return null
        if (producerPath == dependencyPath) return null
        if (!isApiConfiguration(configurationName)) return null
        if (dependencyPath !in FORBIDDEN_CORE) return null
        return "$producerPath re-exports $dependencyPath on the '$configurationName' configuration. " +
            "Use implementation instead: an api dependency would put $dependencyPath on the compile " +
            "classpath of every feature module, which is the rule about :core:data being the only " +
            "data access point, defeated transitively."
    }

    // `api`, plus the per-variant and test-fixture forms AGP derives from it (debugApi, releaseApi,
    // testFixturesApi) — all of them export to consumers, so all of them leak.
    private fun isApiConfiguration(configurationName: String): Boolean =
        configurationName == "api" || configurationName.endsWith("Api")
}
