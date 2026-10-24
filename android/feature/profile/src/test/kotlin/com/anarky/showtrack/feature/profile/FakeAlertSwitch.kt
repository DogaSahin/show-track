package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.feature.profile.alerts.AlertSwitch
import kotlinx.coroutines.flow.MutableStateFlow

/** The alerts switch without WorkManager: it remembers what it was told, or fails as asked. */
class FakeAlertSwitch(
    private val failure: Throwable? = null,
) : AlertSwitch {
    override val enabled = MutableStateFlow(false)
    val writes = mutableListOf<Boolean>()

    override suspend fun setEnabled(enabled: Boolean) {
        failure?.let { throw it }
        writes += enabled
        this.enabled.value = enabled
    }
}
