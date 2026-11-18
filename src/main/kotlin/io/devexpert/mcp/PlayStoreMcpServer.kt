package io.devexpert.mcp

import io.devexpert.playstore.CatalogQueries
import io.devexpert.playstore.ImageCommands
import io.devexpert.playstore.PlayCatalogClient
import io.devexpert.playstore.PlayImageClient
import io.devexpert.playstore.PlayStoreClient
import io.devexpert.playstore.ReleaseCommands
import io.devexpert.playstore.ServiceAccountKeyFile
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
        val keyFile = ServiceAccountKeyFile.validate(keyPath)
        val publisher = lazy { PlayStoreClient.createPublisher(keyFile) }
        val releases = ReleaseCommands(PlayStoreClient { publisher.value })
        val catalog = CatalogQueries(PlayCatalogClient { publisher.value })
        val images = ImageCommands(PlayImageClient { publisher.value })
        PlayStoreTools(releases, catalog, images).registerTools(server)
    }

    private companion object {
        const val KEY_PATH_ENV = "PLAY_STORE_SERVICE_ACCOUNT_KEY_PATH"
    }
}
