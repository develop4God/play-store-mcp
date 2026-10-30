package io.devexpert.mcp

import io.devexpert.playstore.PlayStoreClient
import io.devexpert.playstore.ReleaseCommands
import io.devexpert.tools.PlayStoreTools
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions

class PlayStoreMcpServer {
    private val server = Server(
        serverInfo = Implementation(name = "play-store-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )

    fun getServer(): Server = server

    fun initialize() {
        val keyPath = System.getenv(KEY_PATH_ENV)
            ?: throw IllegalStateException("$KEY_PATH_ENV is not set")
        PlayStoreTools(ReleaseCommands(PlayStoreClient(keyPath))).registerTools(server)
    }

    private companion object {
        const val KEY_PATH_ENV = "PLAY_STORE_SERVICE_ACCOUNT_KEY_PATH"
    }
}
