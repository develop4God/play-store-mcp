package io.devexpert.playstore

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        val result = ReleaseCommands(FakePublisher(releases = listOf(release))).listReleases("com.example.app")

        assertTrue(result.success)
        assertContains(result.message, "\"versionCodes\"")
        assertContains(result.message, "132")
        assertContains(result.message, "internal")
    }

    @Test
    fun `listing releases reports a publisher failure as a failed result like the write commands`() {
        val publisher = FakePublisher(failure = IllegalStateException("quota exceeded"))

        val result = ReleaseCommands(publisher).listReleases("com.example.app")

        assertFalse(result.success)
        assertContains(result.message, "Operation failed")
        assertContains(result.message, "quota exceeded")
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

    // inAppUpdatePriority

    private fun prioritisedDeploy(confirm: Boolean, priority: Int?): DeployRequest =
        deployRequest(confirm).copy(inAppUpdatePriority = priority)

    @Test
    fun `deploy dry run mentions the priority when given and never calls the publisher`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).deploy(prioritisedDeploy(confirm = false, priority = 4))

        assertContains(result.message, "in-app update priority 4")
        assertFalse(result.executed)
        assertTrue(publisher.deployCalls.isEmpty())
    }

    @Test
    fun `deploy dry run does not mention a priority when absent`() {
        val result = ReleaseCommands(FakePublisher()).deploy(prioritisedDeploy(confirm = false, priority = null))

        assertFalse(result.message.contains("priority"))
    }

    @Test
    fun `deploy result mentions the priority when given`() {
        val publisher = FakePublisher()

        val result = ReleaseCommands(publisher).deploy(prioritisedDeploy(confirm = true, priority = 5))

        assertEquals(5, publisher.deployCalls.single().inAppUpdatePriority)
        assertContains(result.message, "In-app update priority: 5")
    }

    @Test
    fun `a production deploy result states the priority before the review notice`() {
        val held = PlayStoreDeploymentResult(
            true, "edit-1", "com.example.app", "production", 141,
            "Uploaded and saved as a draft on production It is NOT sent for review: send it for review in the Play Console."
        )
        val file = File.createTempFile("app", ".aab").also { tempFiles += it }
        val request = DeployRequest("com.example.app", "production", file.absolutePath, emptyMap(), true, 4)

        val message = ReleaseCommands(FakePublisher(deployResult = held)).deploy(request).message

        assertTrue(message.endsWith("send it for review in the Play Console."), message)
        assertContains(message, "In-app update priority: 4\n")
        assertTrue(message.indexOf("In-app update priority") < message.indexOf("NOT sent for review"))
    }

    @Test
    fun `listReleases JSON includes the priority only when set`() {
        val releases = listOf(
            PlayStoreRelease("internal", "completed", listOf(141), null, "r141", inAppUpdatePriority = 4),
            PlayStoreRelease("internal", "completed", listOf(140), null, "r140")
        )

        val message = ReleaseCommands(FakePublisher(releases = releases)).listReleases("com.example.app").message

        assertEquals(1, Regex("inAppUpdatePriority").findAll(message).count())
        assertContains(message, "\"inAppUpdatePriority\": 4")
    }
}
