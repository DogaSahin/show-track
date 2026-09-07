package com.anarky.showtrack.core.database

import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins `@Transaction` on [LibraryDao.replaceAll] — the one property this module exists for — by
 * checking for a side effect of Room's own codegen, because nothing more direct is available.
 *
 * Verified by hand: delete `@Transaction` from `replaceAll` and rebuild. Room then generates NO
 * override of `replaceAll` at all; the interface's un-transacted default body (`clear()` then
 * `insertAll()`, uncoordinated) runs instead, and `testDebugUnitTest` stays green — nothing else
 * in this module notices. The three more obvious approaches were ruled out first:
 * - Runtime reflection on the annotation itself: impossible, `androidx.room.Transaction` is
 *   `RetentionPolicy.CLASS` and is gone by runtime.
 * - `LibraryDao_Impl::class.java.declaredMethods.any { it.name == "replaceAll" }`: does not
 *   discriminate — Kotlin emits a `replaceAll` bridge method on the generated class either way.
 * - Asserting on Room's Flow invalidation/emission timing: timing-dependent on the invalidation
 *   tracker, not a property of the generated code.
 *
 * What DOES differ: with `@Transaction` present, Room generates
 * `replaceAll(...) = performInTransactionSuspending(__db) { super.replaceAll(entries) }`, and the
 * suspend lambda passed to `performInTransactionSuspending` compiles to an anonymous class,
 * `LibraryDao_Impl$replaceAll$2`. Without the annotation, Room emits no `replaceAll` override at
 * all, so that class is never generated. Its presence on the compiled classpath is therefore a
 * real, checkable fact that only holds when the annotation does.
 *
 * **This is brittle by design, not by accident**: it couples to Room's internal naming scheme for
 * a generated anonymous class, which a future Room version could change without breaking any
 * public API. That is the trade this test makes deliberately — failing loudly and
 * self-explanatorily on a Room upgrade beats `@Transaction` silently rotting the first time
 * someone touches this DAO. If a `room` version bump in `libs.versions.toml` breaks this test:
 * 1. Confirm `@Transaction` is still on `LibraryDao.replaceAll` — if it is not, that is the real
 *    bug and this test did its job.
 * 2. If it is, rebuild and inspect the freshly generated
 *    `core/database/build/generated/ksp/debug/kotlin/.../LibraryDao_Impl.kt` for whatever wrapper
 *    class Room now emits for the transaction, and update [TRANSACTION_WRAPPER_CLASS] below to
 *    match. Do not delete this test to make the failure go away.
 */
class LibraryDaoTransactionGuardTest {
    @Test
    fun replaceAll_is_generated_inside_a_transaction_wrapper() {
        try {
            Class.forName(TRANSACTION_WRAPPER_CLASS)
        } catch (cause: ClassNotFoundException) {
            fail(
                "Expected Room to generate $TRANSACTION_WRAPPER_CLASS, which only exists when " +
                    "@Transaction is present on LibraryDao.replaceAll. Room did not generate it, " +
                    "so either @Transaction was removed (put it back) or Room's codegen for the " +
                    "transaction wrapper changed after a version bump (see this class's doc for " +
                    "what to update). Cause: $cause",
            )
        }
    }

    private companion object {
        const val TRANSACTION_WRAPPER_CLASS = "com.anarky.showtrack.core.database.LibraryDao_Impl\$replaceAll\$2"
    }
}
