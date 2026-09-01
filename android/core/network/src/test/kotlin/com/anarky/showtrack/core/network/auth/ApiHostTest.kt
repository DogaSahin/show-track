package com.anarky.showtrack.core.network.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `owns` is the guard that decides whether the user's access token leaves the device, so the
 * cases that matter are the ones where something that is NOT the API looks enough like it to be
 * waved through. It used to compare the host alone.
 */
class ApiHostTest {
    private val apiHost = ApiHost("https://api.showtrack.test/v1/")

    @Test
    fun `a request to the configured origin is ours`() {
        assertTrue(apiHost.owns("https://api.showtrack.test/v1/library".toHttpUrl()))
    }

    @Test
    fun `the default port is the same origin as the explicit one`() {
        // Otherwise pinning the port would break the ordinary case, which is the failure mode a
        // tightened guard actually has.
        assertTrue(apiHost.owns("https://api.showtrack.test:443/v1/library".toHttpUrl()))
    }

    @Test
    fun `a plaintext request to the same host is not ours`() {
        // The downgrade. An attacker on the network answers the http request and reads the Bearer
        // token off the wire; a host-only comparison attached it happily.
        assertFalse(apiHost.owns("http://api.showtrack.test/v1/library".toHttpUrl()))
    }

    @Test
    fun `a different port on the same host is not ours`() {
        // A different service on the same machine is a different trust boundary.
        assertFalse(apiHost.owns("https://api.showtrack.test:8443/v1/library".toHttpUrl()))
    }

    @Test
    fun `a third-party host is not ours`() {
        // The case the class was written for: poster CDNs on a shared client.
        assertFalse(apiHost.owns("https://image.tmdb.org/t/p/w500/poster.jpg".toHttpUrl()))
    }

    @Test
    fun `a host that merely ends with ours is not ours`() {
        assertFalse(apiHost.owns("https://api.showtrack.test.evil.example/v1/library".toHttpUrl()))
    }

    @Test
    fun `host comparison ignores case`() {
        assertTrue(apiHost.owns("https://API.ShowTrack.Test/v1/library".toHttpUrl()))
    }
}
