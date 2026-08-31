package showtrack.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Architecture rule 2, asserted against the classpath that is actually RESOLVED rather than
 * against what a build file declares.
 *
 * [ModuleRules.apiLeakOf] checks declarations, and a declaration check can only ever see the
 * edges someone wrote down as a project path. MEASURED, which is why this task exists: adding
 * `api(libs.retrofit.core)` to `:core:data` configured cleanly and put
 * `com.squareup.retrofit2:retrofit:3.0.0` on `:feature:library`'s `debugCompileClasspath`, with no
 * diagnostic from anywhere. One character — `implementation` to `api` — on a library coordinate,
 * and a feature module could `import retrofit2.*`.
 *
 * Extending the declaration rule to a list of forbidden GROUPS would have closed that one hole.
 * This closes the shape of hole: the resolved graph is the ground truth, so a third-party POM that
 * drags OkHttp in, a new networking library nobody remembered to add to a denylist, and a
 * `testFixturesApi` nobody predicted are all the same question — is it on the compile classpath? —
 * asked once.
 *
 * It also answers the question the configuration-name heuristic was GUESSING at. `testApi` and
 * `androidTestApi` end in "Api" but do not export to a library consumer, so `apiLeakOf` would
 * reject them wrongly; they contribute nothing to `debugCompileClasspath`, so this task simply
 * never sees them.
 *
 * Only the two PRODUCTION classpaths are checked. A feature's own unit tests may legitimately
 * reach for Room — `:core:data`'s do — and the rule is about what `src/main` can compile against.
 *
 * The resolution RESULT, never the artifacts: this needs component identities and no files, so
 * nothing here forces an upstream module to be built. That is what makes it cheap enough to hang
 * off `preBuild`, and hanging it there is what puts it inside `assembleDebug` and
 * `testDebugUnitTest` — both already in the gate and in `android-ci.yml`. A verification task that
 * needs its own gate line is one that gets dropped from the gate.
 */
abstract class VerifyArchitectureClasspath : DefaultTask() {
    @get:Input
    abstract val consumerPath: Property<String>

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val coordinates: SetProperty<String>

    /**
     * A stamp, purely so the task is up-to-date-able. Without an output Gradle re-runs it on every
     * build; with one, an unchanged dependency graph costs nothing after the first run.
     */
    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations =
            coordinates.get().sorted().mapNotNull {
                ModuleRules.forbiddenOnFeatureClasspath(consumerPath.get(), configurationName.get(), it)
            }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n\n"))
        }
        stamp
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(coordinates.get().sorted().joinToString("\n"))
    }
}

/**
 * Every `group:name` in a resolved graph, reached breadth-first from its root.
 *
 * `visited` is keyed on the component id and not on the coordinate: a diamond in the graph would
 * otherwise be walked once per path into it, and a cycle would not terminate at all.
 */
fun flattenModuleCoordinates(root: ResolvedComponentResult): Set<String> {
    val coordinates = sortedSetOf<String>()
    val visited = mutableSetOf(root.id)
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        (component.moduleVersion)?.let { coordinates += "${it.group}:${it.name}" }
        component.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .map { it.selected }
            .filter { visited.add(it.id) }
            .forEach { queue.addLast(it) }
    }
    return coordinates
}
