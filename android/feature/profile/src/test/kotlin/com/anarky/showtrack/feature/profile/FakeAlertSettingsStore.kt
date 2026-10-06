package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAlertSettingsStore(
    enabled: Boolean = false,
) : AlertSettingsStore {
    override val enabled = MutableStateFlow(enabled)
    val fired = mutableSetOf<String>()

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun markFired(name: String): Boolean = fired.add(name)

    override suspend fun clearFired() = fired.clear()
}
