package com.anarky.showtrack.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The client-side mirror of the server's request bounds: each limit, at the limit and one past it. */
class AuthCopyTest {
    private fun fields(
        username: String = "someone",
        email: String = "a@example.com",
        password: String = "hunter2hunter2",
        inviteCode: String = "CODE",
    ) = AuthFormFields().apply {
        this.username = username
        this.email = email
        this.password = password
        this.inviteCode = inviteCode
    }

    @Test
    fun `valid input passes in both modes`() {
        assertNull(validate(AuthMode.LOGIN, fields()))
        assertNull(validate(AuthMode.REGISTER, fields()))
    }

    @Test
    fun `an email with surrounding spaces is accepted like the server does`() {
        assertNull(validate(AuthMode.LOGIN, fields(email = " a@example.com ")))
    }

    @Test
    fun `length limits count characters, not UTF-16 units`() {
        assertNull(validate(AuthMode.REGISTER, fields(username = "😀".repeat(64))))
        assertEquals(
            R.string.auth_validation_username_long,
            validate(AuthMode.REGISTER, fields(username = "😀".repeat(65))),
        )
        // Four emoji are eight UTF-16 units but four characters: too short, as the server sees it.
        assertEquals(
            R.string.auth_validation_password_short,
            validate(AuthMode.REGISTER, fields(password = "😀".repeat(4))),
        )
        assertNull(validate(AuthMode.REGISTER, fields(password = "😀".repeat(8))))
    }

    @Test
    fun `an email the server would reject is caught before sending`() {
        for (email in listOf("bob@localhost", "no-at-sign.com", "a b@example.com", "a@example.")) {
            val result = validate(AuthMode.LOGIN, fields(email = email))
            assertEquals(email, R.string.auth_validation_email_invalid, result)
        }
    }

    @Test
    fun `a password over 256 characters is caught in both modes`() {
        assertNull(validate(AuthMode.LOGIN, fields(password = "p".repeat(256))))
        assertEquals(
            R.string.auth_validation_password_long,
            validate(AuthMode.LOGIN, fields(password = "p".repeat(257))),
        )
        assertEquals(
            R.string.auth_validation_password_long,
            validate(AuthMode.REGISTER, fields(password = "p".repeat(257))),
        )
    }

    @Test
    fun `a username over 64 characters is caught when registering`() {
        assertNull(validate(AuthMode.REGISTER, fields(username = "u".repeat(64))))
        val padded = " ${"u".repeat(64)} "
        assertNull("surrounding spaces do not count", validate(AuthMode.REGISTER, fields(username = padded)))
        assertEquals(
            R.string.auth_validation_username_long,
            validate(AuthMode.REGISTER, fields(username = "u".repeat(65))),
        )
    }

    @Test
    fun `an invite code over 64 characters is caught when registering`() {
        assertNull(validate(AuthMode.REGISTER, fields(inviteCode = "c".repeat(64))))
        assertEquals(
            R.string.auth_validation_invite_code_long,
            validate(AuthMode.REGISTER, fields(inviteCode = "c".repeat(65))),
        )
    }
}
