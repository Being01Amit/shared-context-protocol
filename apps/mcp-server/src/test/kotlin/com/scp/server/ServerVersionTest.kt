package com.scp.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerVersionTest {
    @Test
    fun `uses the manifest version when the build stamped one`() {
        assertEquals("0.3.0", ServerVersion.resolve("0.3.0"))
    }

    @Test
    fun `falls back to the dev version without a real manifest version`() {
        assertEquals(ServerVersion.DEV, ServerVersion.resolve(null))
        assertEquals(ServerVersion.DEV, ServerVersion.resolve(""))
        // Gradle's placeholder when no version was set.
        assertEquals(ServerVersion.DEV, ServerVersion.resolve("unspecified"))
    }
}
