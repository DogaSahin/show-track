import showtrack.buildlogic.ModuleRules

plugins {
    id("showtrack.android.library")
    id("showtrack.android.compose")
}

// Own-project inspection only. Reading another project's state at configuration time breaks the
// configuration cache, which this build has enabled.
project.configurations.configureEach {
    dependencies.configureEach {
        val dependencyPath = (this as? ProjectDependency)?.path ?: return@configureEach
        ModuleRules.violationOf(project.path, dependencyPath)?.let { error(it) }
    }
}
