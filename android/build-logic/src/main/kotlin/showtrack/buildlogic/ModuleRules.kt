package showtrack.buildlogic

/**
 * The two architecture rules, as data. Pure so they can be unit-tested without a Gradle build:
 * a rule that has only ever been observed passing is indistinguishable from one never reached.
 */
object ModuleRules {
    private val FORBIDDEN_CORE = setOf(":core:network", ":core:database")

    /**
     * The library groups the two forbidden core modules own, mapped to the module that owns them.
     * Checked against a RESOLVED feature classpath by [VerifyArchitectureClasspath], never against
     * a declaration — see that class for why the declaration side cannot answer this.
     *
     * Groups rather than exact coordinates: `androidx.room` covers room-runtime, room-ktx and
     * room-paging without an edit, and a Retrofit converter added tomorrow is already listed. The
     * entries are the libraries CLAUDE.md's rule 2 names, plus the two each one drags with it —
     * OkHttp because Retrofit's client is the thing a feature would actually reach for, and
     * `com.jakewharton.retrofit` because the kotlinx-serialization converter is a Retrofit type
     * under someone else's group.
     *
     * MEASURED as non-vacuous and free of false positives: `:core:network`'s own
     * debugCompileClasspath carries four of these groups and `:core:database`'s carries
     * androidx.room, while all nine `:feature:*` modules carry none of them — Coil's OkHttp does
     * not reach a feature, because it is implementation-scoped inside `:core:designsystem`.
     */
    private val FORBIDDEN_GROUPS =
        mapOf(
            "com.squareup.retrofit2" to ":core:network",
            "com.squareup.okhttp3" to ":core:network",
            "com.jakewharton.retrofit" to ":core:network",
            "androidx.room" to ":core:database",
        )

    fun violationOf(
        consumerPath: String,
        dependencyPath: String,
    ): String? {
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
    fun apiLeakOf(
        producerPath: String,
        configurationName: String,
        dependencyPath: String,
    ): String? {
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
    //
    // KNOWN OVER-BROAD, and deliberately left so: this also matches `testApi` and `androidTestApi`,
    // which do NOT export to a library consumer, so a :core:* module wanting
    // `androidTestApi(project(":core:network"))` would be rejected wrongly. Narrowing it by name
    // would be guessing at what exports; [VerifyArchitectureClasspath] answers that from the
    // resolved graph instead, and a test configuration is simply absent from it. This heuristic
    // stays because it fails EARLIER and with a better message for the case it does cover — a
    // project-path `api` edge — not because it is the authority on what leaks.
    private fun isApiConfiguration(configurationName: String): Boolean =
        configurationName == "api" || configurationName.endsWith("Api")

    /**
     * Rule 2 arriving from the third side: not what a feature declares, not what a core module
     * re-exports as a project path, but what actually LANDS on the feature's compile classpath.
     *
     * `coordinate` is `group:name`. The group alone decides it — see [FORBIDDEN_GROUPS].
     */
    fun forbiddenOnFeatureClasspath(
        consumerPath: String,
        configurationName: String,
        coordinate: String,
    ): String? {
        if (!consumerPath.startsWith(":feature:")) return null
        val owner = FORBIDDEN_GROUPS[coordinate.substringBefore(':')] ?: return null
        return "$coordinate is on $consumerPath\'s \'$configurationName\'. That artifact belongs to " +
            "$owner, so a feature module can now compile against it directly — architecture rule 2 " +
            "defeated transitively. Find the api() edge that re-exports it (an api(project(...)) or " +
            "an api() on the library coordinate itself, in $owner or in a module between it and " +
            "here) and make it implementation()."
    }
}
