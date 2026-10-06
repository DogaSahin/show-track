package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

class FakeAlertSettingsStore(
    enabled: Boolean = false,
) : AlertSettingsStore {
    override val enabled = MutableStateFlow(enabled)
    val fired = mutableSetOf<String>()
    var key = "key-1"

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun alertKey(): String = key

    override suspend fun markFired(
        name: String,
        airsAt: Instant,
        now: Instant,
    ): Boolean = fired.add(name)

    override suspend fun forgetAccount() {
        fired.clear()
        key += "'"
    }
}
