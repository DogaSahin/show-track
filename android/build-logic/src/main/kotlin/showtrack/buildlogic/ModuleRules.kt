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
}
