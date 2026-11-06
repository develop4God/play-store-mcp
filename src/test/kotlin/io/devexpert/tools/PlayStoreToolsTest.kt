package io.devexpert.tools

import io.devexpert.playstore.FakePublisher
import io.devexpert.playstore.PlayStoreRelease
import io.devexpert.playstore.ReleaseCommands
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayStoreToolsTest {
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    private fun bundle() = File.createTempFile("app", ".aab").also { tempFiles += it }.absolutePath

    private fun <T> withClient(publisher: FakePublisher, block: suspend (Client) -> T): T = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val server = Server(
                Implementation("test-server", "0"),
                ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            )
            PlayStoreTools(ReleaseCommands(publisher)).registerTools(server)
            val client = Client(Implementation("test-client", "0"))
            val (clientSide, serverSide) = InMemoryTransport.linkedPair()
            server.connect(serverSide)
            client.connect(clientSide)
            try {
                block(client)
            } finally {
                client.close()
                server.close()
            }
        }
    }

    private fun CallToolResultBase.text() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

    @Test
    fun `registers the four tools`() {
        val names = withClient(FakePublisher()) { client -> client.listTools()!!.tools.map { it.name } }

        assertEquals(setOf("deploy_app", "promote_release", "update_release_notes", "get_releases"), names.toSet())
    }

    @Test
    fun `deploy_app schema documents inAppUpdatePriority as an optional integer`() {
        val schema = withClient(FakePublisher()) { client ->
            client.listTools()!!.tools.single { it.name == "deploy_app" }.inputSchema
        }

        val property = schema.properties["inAppUpdatePriority"]!!.jsonObject
        assertEquals("integer", property["type"]!!.jsonPrimitive.content)
        assertContains(property["description"]!!.jsonPrimitive.content, "urgent")
        assertFalse("inAppUpdatePriority" in schema.required.orEmpty())
    }

    @Test
    fun `promote_release description says the priority is kept`() {
        val description = withClient(FakePublisher()) { client ->
            client.listTools()!!.tools.single { it.name == "promote_release" }.description
        }

        assertContains(description.orEmpty(), "in-app update priority")
    }

    @Test
    fun `deploy_app without confirm is a dry run and does not reach the publisher`() {
        val publisher = FakePublisher()
        val args = mapOf("packageName" to "com.example.app", "track" to "internal", "apkPath" to bundle())

        val result = withClient(publisher) { it.callTool("deploy_app", args)!! }

        assertNotEquals(true, result.isError)
        assertContains(result.text(), "DRY RUN")
        assertTrue(publisher.deployCalls.isEmpty())
    }

    @Test
    fun `deploy_app with confirm calls the publisher`() {
        val publisher = FakePublisher()
        val args = mapOf("packageName" to "com.example.app", "track" to "beta", "apkPath" to bundle(), "confirm" to true)

        val result = withClient(publisher) { it.callTool("deploy_app", args)!! }

        assertContains(result.text(), "SUCCESS")
        assertEquals(1, publisher.deployCalls.size)
        assertEquals("beta", publisher.deployCalls.single().track)
    }

    @Test
    fun `invalid request is an error result and never reaches the publisher`() {
        val publisher = FakePublisher()
        val args = mapOf("packageName" to "not a package", "track" to "internal", "apkPath" to bundle(), "confirm" to true)

        val result = withClient(publisher) { it.callTool("deploy_app", args)!! }

        assertEquals(true, result.isError)
        assertContains(result.text(), "Invalid request")
        assertTrue(publisher.deployCalls.isEmpty())
    }

    @Test
    fun `promotion to production is rejected`() {
        val publisher = FakePublisher()
        val args = mapOf(
            "packageName" to "com.example.app",
            "fromTrack" to "beta",
            "toTrack" to "production",
            "versionCode" to 141,
            "confirm" to true,
        )

        val result = withClient(publisher) { it.callTool("promote_release", args)!! }

        assertEquals(true, result.isError)
        assertTrue(publisher.promoteCalls.isEmpty())
    }

    @Test
    fun `promote_release with confirm calls the publisher`() {
        val publisher = FakePublisher()
        val args = mapOf(
            "packageName" to "com.example.app",
            "fromTrack" to "internal",
            "toTrack" to "alpha",
            "versionCode" to 141,
            "confirm" to true,
        )

        val result = withClient(publisher) { it.callTool("promote_release", args)!! }

        assertNotEquals(true, result.isError)
        assertEquals(141L, publisher.promoteCalls.single().versionCode)
    }

    @Test
    fun `update_release_notes with empty notes is rejected`() {
        val publisher = FakePublisher()
        val args = mapOf(
            "packageName" to "com.example.app",
            "track" to "beta",
            "versionCode" to 141,
            "releaseNotes" to buildJsonObject { },
            "confirm" to true,
        )

        val result = withClient(publisher) { it.callTool("update_release_notes", args)!! }

        assertEquals(true, result.isError)
        assertTrue(publisher.notesCalls.isEmpty())
    }

    @Test
    fun `update_release_notes with confirm calls the publisher`() {
        val publisher = FakePublisher()
        val args = mapOf(
            "packageName" to "com.example.app",
            "track" to "beta",
            "versionCode" to 141,
            "releaseNotes" to buildJsonObject { put("en-US", "Fixes") },
            "confirm" to true,
        )

        val result = withClient(publisher) { it.callTool("update_release_notes", args)!! }

        assertNotEquals(true, result.isError)
        assertEquals(mapOf("en-US" to "Fixes"), publisher.notesCalls.single().releaseNotes)
    }

    @Test
    fun `get_releases returns the releases as JSON`() {
        val release = PlayStoreRelease("internal", "completed", listOf(141L), null, "1.0")
        val publisher = FakePublisher(releases = listOf(release))

        val result = withClient(publisher) { it.callTool("get_releases", mapOf("packageName" to "com.example.app"))!! }

        assertFalse(result.isError == true)
        assertContains(result.text(), "\"track\": \"internal\"")
        assertEquals(listOf("com.example.app"), publisher.listCalls)
    }

    @Test
    fun `get_releases without packageName is an error result`() {
        val publisher = FakePublisher()

        val result = withClient(publisher) { it.callTool("get_releases", emptyMap())!! }

        assertEquals(true, result.isError)
        assertContains(result.text(), "packageName is required")
        assertTrue(publisher.listCalls.isEmpty())
    }

    @Test
    fun `publisher failure becomes an error result instead of crashing the server`() {
        val publisher = FakePublisher(failure = IllegalStateException("quota exceeded"))

        val result = withClient(publisher) { it.callTool("get_releases", mapOf("packageName" to "com.example.app"))!! }

        assertEquals(true, result.isError)
        assertContains(result.text(), "quota exceeded")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
