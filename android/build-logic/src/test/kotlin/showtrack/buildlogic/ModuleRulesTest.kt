package showtrack.buildlogic

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleRulesTest {
    @Test
    fun `a feature depending on another feature is a violation naming both`() {
        val message = ModuleRules.violationOf(":feature:feed", ":feature:detail")
        assertTrue(message != null && message.contains(":feature:feed") && message.contains(":feature:detail"))
    }

    @Test
    fun `a feature depending on core network is a violation`() {
        assertTrue(ModuleRules.violationOf(":feature:library", ":core:network") != null)
    }

    @Test
    fun `a feature depending on core database is a violation`() {
        assertTrue(ModuleRules.violationOf(":feature:library", ":core:database") != null)
    }

    @Test
    fun `a feature depending on core data is allowed`() {
        assertNull(ModuleRules.violationOf(":feature:library", ":core:data"))
    }

    @Test
    fun `core data depending on core network is allowed`() {
        assertNull(ModuleRules.violationOf(":core:data", ":core:network"))
    }

    @Test
    fun `a feature depending on itself is allowed, because AGP adds that edge for test variants`() {
        assertNull(ModuleRules.violationOf(":feature:library", ":feature:library"))
    }

    @Test
    fun `app depending on a feature is allowed`() {
        assertNull(ModuleRules.violationOf(":app", ":feature:library"))
    }

    @Test
    fun `core data re-exporting core network on api is a violation naming both`() {
        val message = ModuleRules.apiLeakOf(":core:data", "api", ":core:network")
        assertTrue(message != null && message.contains(":core:data") && message.contains(":core:network"))
    }

    @Test
    fun `core data re-exporting core database on a per-variant api configuration is a violation`() {
        assertTrue(ModuleRules.apiLeakOf(":core:data", "debugApi", ":core:database") != null)
    }

    @Test
    fun `core data depending on core network on implementation is allowed`() {
        assertNull(ModuleRules.apiLeakOf(":core:data", "implementation", ":core:network"))
    }

    @Test
    fun `core data re-exporting core model on api is allowed`() {
        assertNull(ModuleRules.apiLeakOf(":core:data", "api", ":core:model"))
    }

    @Test
    fun `app re-exporting core network on api is not this rule's business`() {
        assertNull(ModuleRules.apiLeakOf(":app", "api", ":core:network"))
    }

    @Test
    fun `a retrofit artifact on a feature classpath is a violation naming the owning module`() {
        val message =
            ModuleRules.forbiddenOnFeatureClasspath(
                ":feature:library",
                "debugCompileClasspath",
                "com.squareup.retrofit2:retrofit",
            )
        assertTrue(message != null && message.contains(":feature:library") && message.contains(":core:network"))
    }

    @Test
    fun `a room artifact on a feature classpath is a violation naming core database`() {
        val message =
            ModuleRules.forbiddenOnFeatureClasspath(
                ":feature:detail",
                "releaseCompileClasspath",
                "androidx.room:room-ktx",
            )
        assertTrue(message != null && message.contains(":core:database"))
    }

    @Test
    fun `an okhttp artifact on a feature classpath is a violation, since it arrives transitively`() {
        // The case a declaration-side denylist could not reach at all: nobody DECLARES okhttp, it
        // comes in under Retrofit. Measured on the real build — one `api(libs.retrofit.core)` in
        // :core:data put both retrofit AND okhttp on :feature:library's classpath.
        assertTrue(
            ModuleRules.forbiddenOnFeatureClasspath(
                ":feature:library",
                "debugCompileClasspath",
                "com.squareup.okhttp3:okhttp",
            ) != null,
        )
    }

    @Test
    fun `an ordinary artifact on a feature classpath is allowed`() {
        assertNull(
            ModuleRules.forbiddenOnFeatureClasspath(
                ":feature:library",
                "debugCompileClasspath",
                "androidx.compose.ui:ui",
            ),
        )
    }

    @Test
    fun `a retrofit artifact on a core module's own classpath is not this rule's business`() {
        // :core:network is WHERE Retrofit is supposed to be. The rule is about feature modules
        // only, which is also what keeps it from firing on :core:data's runtime classpath.
        assertNull(
            ModuleRules.forbiddenOnFeatureClasspath(
                ":core:network",
                "debugCompileClasspath",
                "com.squareup.retrofit2:retrofit",
            ),
        )
    }

    @Test
    fun `a group that merely starts like a forbidden one is allowed`() {
        // Exact group match, not a prefix: `androidx.roomba` is nobody's Room.
        assertNull(
            ModuleRules.forbiddenOnFeatureClasspath(
                ":feature:library",
                "debugCompileClasspath",
                "androidx.roomba:core",
            ),
        )
    }

    @Test
    fun `core network re-exporting itself is allowed`() {
        assertNull(ModuleRules.apiLeakOf(":core:network", "api", ":core:network"))
    }
}
