package com.anarky.showtrack

import com.anarky.showtrack.core.data.group.ActiveGroupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [ActiveGroupStore] — no DataStore, no Context. Shared by [ActiveGroupViewModelTest]
 * and [GroupSwitchNavHostTest]: `internal`, not `private`, specifically so both can use the SAME
 * declaration rather than two file-private ones with identical names (Kotlin's top-level `private`
 * scopes visibility, not the symbol itself — two files in the same package each declaring `private
 * class FakeActiveGroupStore` collide at compile time; measured, not assumed).
 */
internal class FakeActiveGroupStore(
    initial: String?,
) : ActiveGroupStore {
    private val mutableFlow = MutableStateFlow(initial)
    override val activeGroupId: Flow<String?> = mutableFlow

    val setCalls = mutableListOf<String?>()

    override suspend fun setActiveGroup(groupId: String?) {
        setCalls += groupId
        mutableFlow.value = groupId
    }
}
