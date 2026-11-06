package io.devexpert.playstore

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayStoreClientTest {
    private val api = FakePlayApi()
    private val client = PlayStoreClient { api.publisher }
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    private fun bundle(extension: String) = File.createTempFile("app", ".$extension").also { tempFiles += it }.absolutePath

    private fun release(status: String, vararg codes: Int, notes: String? = null) =
        """{"name": "r${codes.first()}", "status": "$status", "versionCodes": [${codes.joinToString(",") { "\"$it\"" }}]""" +
            (notes?.let { ""","releaseNotes": [{"language": "en-US", "text": "$it"}]""" } ?: "") + "}"

    private fun track(name: String, vararg releases: String) = """{"track": "$name", "releases": [${releases.joinToString(",")}]}"""

    private fun deploy(track: String, extension: String = "aab", notes: Map<String, String> = emptyMap()) =
        client.deploy(DeployRequest("com.example.app", track, bundle(extension), notes, true))

    private fun lastTrackUpdate() = api.requestsTo("PUT", "/tracks/").last().body

    // listReleases

    @Test
    fun `listReleases flattens every release of every track and deletes the edit`() {
        api.tracks["internal"] = track("internal", release("completed", 140), release("draft", 141))
        api.tracks["beta"] = track("beta", release("completed", 139))

        val releases = client.listReleases("com.example.app")

        assertEquals(listOf("internal", "internal", "beta"), releases.map { it.track })
        assertEquals(listOf(141L), releases[1].versionCodes)
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `listReleases still deletes the edit when listing tracks fails`() {
        api.fail = { method, url -> if (method == "GET" && url.endsWith("/tracks")) 500 else null }

        assertFailsWith<Exception> { client.listReleases("com.example.app") }

        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
    }

    @Test
    fun `listReleases with no tracks returns an empty list`() {
        assertTrue(client.listReleases("com.example.app").isEmpty())
    }

    // deploy

    @Test
    fun `deploy of an aab uses the bundles endpoint and returns the version code from the file`() {
        val result = deploy("internal")

        assertTrue(result.success)
        assertEquals(141L, result.versionCode)
        assertTrue(api.requestsTo("POST", "/bundles").isNotEmpty())
        assertTrue(api.requestsTo("POST", "/apks").isEmpty())
        assertContains(result.message, "released to internal")
    }

    @Test
    fun `deploy of an apk uses the apks endpoint`() {
        val result = deploy("beta", extension = "apk")

        assertTrue(result.success)
        assertTrue(api.requestsTo("POST", "/apks").isNotEmpty())
        assertTrue(api.requestsTo("POST", "/bundles").isEmpty())
    }

    @Test
    fun `deploy on a testing track completes the release and sends the edit for review`() {
        deploy("internal", notes = mapOf("en-US" to "Fixes"))

        assertContains(lastTrackUpdate(), "\"status\":\"completed\"")
        assertContains(lastTrackUpdate(), "Fixes")
        assertContains(api.requestsTo("POST", ":commit").single().url, "changesNotSentForReview=false")
    }

    @Test
    fun `deploy on production saves a draft and commits without sending for review`() {
        val result = deploy("production")

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "\"status\":\"draft\"")
        assertContains(api.requestsTo("POST", ":commit").single().url, "changesNotSentForReview=true")
        assertContains(result.message, "NOT sent for review")
    }

    @Test
    fun `deploy without release notes sends none`() {
        deploy("internal")

        assertFalse(lastTrackUpdate().contains("releaseNotes"))
    }

    @Test
    fun `deploy keeps in-progress work already on the track`() {
        api.tracks["internal"] = track("internal", release("inProgress", 130), release("completed", 120))

        deploy("internal")

        val sent = lastTrackUpdate()
        assertContains(sent, "130")
        assertContains(sent, "141")
    }

    @Test
    fun `deploy works when the track does not exist yet`() {
        val result = deploy("alpha")

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "141")
    }

    @Test
    fun `a failed upload deletes the edit, never commits and reports the failure`() {
        api.fail = { _, url -> if (url.contains("uploadType")) 500 else null }

        val result = deploy("internal")

        assertFalse(result.success)
        assertNull(result.versionCode)
        assertContains(result.message, "Operation failed")
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `a non-404 error while reading the track fails the deploy`() {
        api.fail = { method, url -> if (method == "GET" && url.contains("/tracks/")) 500 else null }

        val result = deploy("internal")

        assertFalse(result.success)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `a failed commit reports failure`() {
        api.fail = { _, url -> if (url.contains(":commit")) 500 else null }

        assertFalse(deploy("internal").success)
    }

    // promote

    private fun promote(from: String = "internal", to: String = "beta", code: Long = 141) =
        client.promote(PromoteRequest("com.example.app", from, to, code, true))

    @Test
    fun `promote copies a completed release with its notes to the target track`() {
        api.tracks["internal"] = track("internal", release("completed", 141, notes = "Hello"))

        val result = promote()

        assertTrue(result.success)
        assertEquals(141L, result.versionCode)
        assertContains(lastTrackUpdate(), "\"status\":\"completed\"")
        assertContains(lastTrackUpdate(), "Hello")
        assertContains(api.requestsTo("PUT", "/tracks/beta").single().url, "/tracks/beta")
    }

    @Test
    fun `promote fails when the version is not on the source track`() {
        api.tracks["internal"] = track("internal", release("completed", 100))

        val result = promote()

        assertFalse(result.success)
        assertContains(result.message, "not found in internal")
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
    }

    @Test
    fun `promote refuses a release that is not completed`() {
        api.tracks["internal"] = track("internal", release("draft", 141))

        val result = promote()

        assertFalse(result.success)
        assertContains(result.message, "Only completed releases can be promoted")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `promote fails when the source track does not exist`() {
        assertFalse(promote().success)
    }

    // updateReleaseNotes

    private fun updateNotes(track: String = "beta", code: Long = 141) = client.updateReleaseNotes(
        UpdateReleaseNotesRequest("com.example.app", track, code, mapOf("es-ES" to "Nuevo"), true),
    )

    @Test
    fun `updateReleaseNotes replaces the notes of a matching release`() {
        api.tracks["beta"] = track("beta", release("completed", 141, notes = "Old"))

        val result = updateNotes()

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "Nuevo")
        assertFalse(lastTrackUpdate().contains("Old"))
    }

    @Test
    fun `updateReleaseNotes prefers the draft when a version appears twice`() {
        api.tracks["beta"] = track("beta", release("completed", 141, notes = "Live"), release("draft", 141, notes = "Draft"))

        updateNotes()

        assertContains(lastTrackUpdate(), "Live")
        assertFalse(lastTrackUpdate().contains("Draft"))
        assertContains(lastTrackUpdate(), "Nuevo")
    }

    @Test
    fun `updateReleaseNotes reports a track with no releases`() {
        val result = updateNotes()

        assertFalse(result.success)
        assertContains(result.message, "has no releases")
    }

    @Test
    fun `updateReleaseNotes reports a version that is not on the track`() {
        api.tracks["beta"] = track("beta", release("completed", 100))

        val result = updateNotes()

        assertFalse(result.success)
        assertContains(result.message, "not found in beta")
    }

    @Test
    fun `updateReleaseNotes on production only edits drafts`() {
        api.tracks["production"] = track("production", release("completed", 141))

        val result = updateNotes(track = "production")

        assertFalse(result.success)
        assertContains(result.message, "Only draft releases can be edited")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `updateReleaseNotes on a production draft commits without sending for review`() {
        api.tracks["production"] = track("production", release("draft", 141))

        val result = updateNotes(track = "production")

        assertTrue(result.success)
        assertContains(api.requestsTo("POST", ":commit").single().url, "changesNotSentForReview=true")
    }

    @Test
    fun `updateReleaseNotes passes through a non-404 API error as a failure`() {
        api.fail = { method, url -> if (method == "GET" && url.contains("/tracks/")) 500 else null }

        assertFalse(updateNotes().success)
    }

    // initialization

    @Test
    fun `a missing key file fails on first use with a clear message`() {
        val broken = PlayStoreClient("/nonexistent/key.json")

        val e = assertFailsWith<PlayStoreException> { broken.listReleases("com.example.app") }

        assertContains(e.message.orEmpty(), "not found")
    }

    @Test
    fun `an invalid key file is reported as an initialization failure`() {
        val key = File.createTempFile("key", ".json").also { tempFiles += it }
        key.writeText("not json")

        val e = assertFailsWith<PlayStoreException> { PlayStoreClient(key.absolutePath).listReleases("com.example.app") }

        assertContains(e.message.orEmpty(), "Failed to initialize API client")
    }

    // inAppUpdatePriority

    @Test
    fun `deploy with a priority sends it on the new release`() {
        client.deploy(DeployRequest("com.example.app", "internal", bundle("aab"), emptyMap(), true, inAppUpdatePriority = 4))

        assertContains(lastTrackUpdate(), "\"inAppUpdatePriority\":4")
    }

    @Test
    fun `deploy without a priority does not send the field`() {
        deploy("internal")

        assertFalse(lastTrackUpdate().contains("inAppUpdatePriority"))
    }

    @Test
    fun `promote keeps the priority of the source release`() {
        api.tracks["internal"] = track(
            "internal",
            """{"name": "r141", "status": "completed", "versionCodes": ["141"], "inAppUpdatePriority": 5}""",
        )

        val result = promote()

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "\"inAppUpdatePriority\":5")
        assertContains(result.message, "in-app update priority 5 (URGENT: apps may force an immediate update)")
    }

    @Test
    fun `promote keeps an explicit priority of 0`() {
        api.tracks["internal"] = track(
            "internal",
            """{"name": "r141", "status": "completed", "versionCodes": ["141"], "inAppUpdatePriority": 0}""",
        )

        val result = promote()

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "\"inAppUpdatePriority\":0")
    }

    @Test
    fun `promote of a release without a priority does not send the field`() {
        api.tracks["internal"] = track("internal", release("completed", 141))

        val result = promote()

        assertTrue(result.success)
        assertFalse(lastTrackUpdate().contains("inAppUpdatePriority"))
        assertFalse(result.message.contains("priority"), result.message)
    }

    @Test
    fun `updateReleaseNotes keeps the priority of the edited release`() {
        api.tracks["beta"] = track(
            "beta",
            """{"name": "r141", "status": "completed", "versionCodes": ["141"], "inAppUpdatePriority": 5}""",
        )

        val result = updateNotes()

        assertTrue(result.success)
        assertContains(lastTrackUpdate(), "\"inAppUpdatePriority\":5")
    }

    @Test
    fun `listReleases reports the priority only when Play stored one`() {
        api.tracks["internal"] = track(
            "internal",
            """{"name": "r141", "status": "completed", "versionCodes": ["141"], "inAppUpdatePriority": 4}""",
            release("completed", 140),
        )

        val releases = client.listReleases("com.example.app")

        assertEquals(listOf(4, null), releases.map { it.inAppUpdatePriority })
    }
}
