package io.devexpert.mcp

import kotlin.test.Test
import kotlin.test.assertNotNull

class PlayStoreMcpServerTest {
    @Test
    fun `exposes a server instance`() {
        assertNotNull(PlayStoreMcpServer().getServer())
    }
}
