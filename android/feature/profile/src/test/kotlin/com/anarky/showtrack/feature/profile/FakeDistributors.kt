package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.feature.profile.push.DistributorSource

/** Shared by [ProfileViewModelTest] and [ProfileResumeTest] — both construct a [ProfileViewModel]. */
internal class FakeDistributors(
    var installed: List<String> = emptyList(),
    var saved: String? = null,
) : DistributorSource {
    var unregistered = false

    override fun available(): List<String> = installed

    override fun selected(): String? = saved

    override fun register(packageName: String) {
        saved = packageName
    }

    override fun unregister() {
        saved = null
        unregistered = true
    }
}
