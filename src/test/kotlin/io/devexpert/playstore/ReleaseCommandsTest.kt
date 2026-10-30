package io.devexpert.playstore

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePublisher(
    private val deployResult: PlayStoreDeploymentResult? = null,
    private val releases: List<PlayStoreRelease> = emptyList()
) : PlayPublisher {
    val deployCalls = mutableListOf<DeployRequest>()
    val promoteCalls = mutableListOf<PromoteRequest>()
    val notesCalls = mutableListOf<UpdateReleaseNotesRequest>()
    val listCalls = mutableListOf<String>()

    override fun listReleases(packageName: String): List<PlayStoreRelease> {
        listCalls += packageName
        return releases
    }

    override fun deploy(request: DeployRequest): PlayStoreDeploymentResult {
        deployCalls += request
        return deployResult ?: PlayStoreDeploymentResult(true, "edit-1", request.packageName, request.track, 141, "ok")
    }

    override fun promote(request: PromoteRequest): PlayStoreDeploymentResult {
        promoteCalls += request
        return PlayStoreDeploymentResult(true, "edit-2", request.packageName, request.toTrack, request.versionCode, "ok")
    }

    override fun updateReleaseNotes(request: UpdateReleaseNotesRequest): PlayStoreDeploymentResult {
        notesCalls += request
        return PlayStoreDeploymentResult(true, "edit-3", request.packageName, request.track, request.versionCode, "ok")
    }
}

class ReleaseCommandsTest {
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    private fun deployRequest(confirm: Boolean): DeployRequest {
        val file = File.createTempFile("app", ".aab").also { tempFiles += it }
        return DeployRequest("com.example.app", "internal", file.absolutePath, emptyMap(), confirm)
    }

    private fun promoteRequest(confirm: Boolean) =
        PromoteRequest("com.example.app", "internal", "alpha", 141, confirm)

    @Test
    fun `deploy without confirm is a dry run that never calls the publisher`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).deploy(deployRequest(confirm = false))

        assertTrue(publisher.deployCalls.isEmpty())
        assertFalse(result.executed)
        assertContains(result.message, "DRY RUN")
        assertContains(result.message, "confirm=true")
        assertContains(result.message, "internal")
    }

    @Test
    fun `dry run says what it did not check so it gives no false assurance`() {
        val result = ReleaseCommands(FakePublisher()).promote(promoteRequest(confirm = false))

        assertContains(result.message, "not checked")
    }

    @Test
    fun `deploy with confirm calls the publisher exactly once`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).deploy(deployRequest(confirm = true))

        assertEquals(1, publisher.deployCalls.size)
        assertTrue(result.executed)
        assertContains(result.message, "141")
    }

    @Test
    fun `a failed deploy is reported as failure not success`() {
        val failure = PlayStoreDeploymentResult(false, null, "com.example.app", "internal", null, "Deployment failed: 403")
        val result = ReleaseCommands(FakePublisher(deployResult = failure)).deploy(deployRequest(confirm = true))

        assertTrue(result.executed)
        assertFalse(result.success)
        assertContains(result.message, "403")
    }

    @Test
    fun `promote without confirm is a dry run`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).promote(promoteRequest(confirm = false))

        assertTrue(publisher.promoteCalls.isEmpty())
        assertFalse(result.executed)
        assertContains(result.message, "DRY RUN")
    }

    @Test
    fun `promote with confirm calls the publisher once`() {
        val publisher = FakePublisher()

        ReleaseCommands(publisher).promote(promoteRequest(confirm = true))

        assertEquals(1, publisher.promoteCalls.size)
    }

    @Test
    fun `listing releases with an invalid package name fails before any call`() {
        val publisher = FakePublisher()

        assertFailsWith<InvalidRequestException> { ReleaseCommands(publisher).listReleases("not a package") }

        assertTrue(publisher.listCalls.isEmpty())
    }

    @Test
    fun `listing releases returns json with real version codes`() {
        val release = PlayStoreRelease("internal", "completed", listOf(132L), null, "Release 132")

        val json = ReleaseCommands(FakePublisher(releases = listOf(release))).listReleases("com.example.app")

        assertContains(json, "\"versionCodes\"")
        assertContains(json, "132")
        assertContains(json, "internal")
    }

    private fun notesRequest(confirm: Boolean) =
        UpdateReleaseNotesRequest("com.example.app", "beta", 141, mapOf("en-US" to "Fixes"), confirm)

    @Test
    fun `release notes update without confirm is a dry run`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).updateReleaseNotes(notesRequest(confirm = false))

        assertTrue(publisher.notesCalls.isEmpty())
        assertFalse(result.executed)
        assertContains(result.message, "DRY RUN")
    }

    @Test
    fun `release notes update with confirm calls the publisher once`() {
        val publisher = FakePublisher()

        ReleaseCommands(publisher).updateReleaseNotes(notesRequest(confirm = true))

        assertEquals(1, publisher.notesCalls.size)
    }

    @Test
    fun `a production dry run says it will only be saved as a draft not sent for review`() {
        val file = File.createTempFile("app", ".aab").also { tempFiles += it }
        val request = DeployRequest("com.example.app", "production", file.absolutePath, emptyMap(), false)

        val result = ReleaseCommands(FakePublisher()).deploy(request)

        assertContains(result.message, "draft")
        assertContains(result.message, "NOT sent for review")
    }

    @Test
    fun `a production notes dry run says only drafts are edited and nothing is sent for review`() {
        val request = UpdateReleaseNotesRequest("com.example.app", "production", 141, mapOf("en-US" to "x"), false)

        val result = ReleaseCommands(FakePublisher()).updateReleaseNotes(request)

        assertContains(result.message, "draft")
        assertContains(result.message, "NOT sent for review")
    }
}
