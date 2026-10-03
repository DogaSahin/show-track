package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.model.ActiveGroupState

/** An account in no group: what the profile tests that do not care about groups pass in. */
internal val NO_GROUPS = ActiveGroupState.Success(groups = emptyList(), activeGroupId = null)
