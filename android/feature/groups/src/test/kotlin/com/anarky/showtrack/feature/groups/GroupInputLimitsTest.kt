package com.anarky.showtrack.feature.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupInputLimitsTest {
    @Test
    fun `a group name is accepted up to 64 characters`() {
        assertNull(groupNameError("n".repeat(64)))
        assertEquals(R.string.groups_validation_name_long, groupNameError("n".repeat(65)))
    }

    @Test
    fun `a group name counts code points the way the server does`() {
        assertNull(groupNameError("😀".repeat(64)))
        assertEquals(R.string.groups_validation_name_long, groupNameError("😀".repeat(65)))
    }

    @Test
    fun `an invite code is accepted up to 64 characters`() {
        assertNull(inviteCodeError("c".repeat(64)))
        assertEquals(R.string.groups_validation_code_long, inviteCodeError("c".repeat(65)))
    }

    @Test
    fun `an invite code counts code points the way the server does`() {
        assertNull(inviteCodeError("😀".repeat(64)))
        assertEquals(R.string.groups_validation_code_long, inviteCodeError("😀".repeat(65)))
    }
}
