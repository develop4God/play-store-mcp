package io.devexpert.playstore

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCommandsTest {
    private val images = TestImages()
    private val publisher = FakeImagePublisher()
    private val commands = ImageCommands(publisher)

    @AfterTest
    fun cleanUp() = images.close()

    private fun request(confirm: Boolean, sendForReview: Boolean = false) = ReplaceStoreImagesRequest(
        "com.example.app",
        listOf(
            ImageReplacement("en-US", "phoneScreenshots", listOf(images.png("one.png", 1080, 1920).path, images.png("two.png", 1080, 1920).path)),
            ImageReplacement("es-419", "icon", listOf(images.png("icon.png", 512, 512).path))
        ),
        confirm,
        sendForReview
    )

    @Test
    fun `a dry run describes every slot and never reaches Play`() {
        val result = commands.replaceImages(request(confirm = false))

        assertFalse(result.executed)
        assertTrue(result.success)
        assertContains(result.message, "DRY RUN")
        assertContains(result.message, "en-US phoneScreenshots: one.png (1080x1920")
        assertContains(result.message, "es-419 icon: icon.png (512x512")
        assertTrue(publisher.calls.isEmpty())
    }

    @Test
    fun `a dry run lists the files in upload order`() {
        val message = commands.replaceImages(request(confirm = false)).message

        assertTrue(message.indexOf("one.png") < message.indexOf("two.png"))
    }

    @Test
    fun `the dry run says whether it will be held back or sent to review`() {
        assertContains(commands.replaceImages(request(confirm = false)).message, "NOT sent for review")
        assertContains(commands.replaceImages(request(confirm = false, sendForReview = true)).message, "SENT TO GOOGLE REVIEW")
        assertFalse(commands.replaceImages(request(confirm = false, sendForReview = true)).message.contains("NOT sent"))
    }

    @Test
    fun `confirm sends exactly one request to Play, whatever the number of slots`() {
        val result = commands.replaceImages(request(confirm = true))

        assertTrue(result.executed)
        assertTrue(result.success)
        assertContains(result.message, "SUCCESS")
        assertEquals(1, publisher.calls.size)
        assertEquals(2, publisher.calls.single().replacements.size)
    }

    @Test
    fun `sendForReview reaches the publisher only when asked for`() {
        commands.replaceImages(request(confirm = true))
        commands.replaceImages(request(confirm = true, sendForReview = true))

        assertEquals(listOf(false, true), publisher.calls.map { it.sendForReview })
    }

    @Test
    fun `a failed write is reported as failed`() {
        val failing = object : PlayImagePublisher {
            override fun replaceImages(request: ReplaceStoreImagesRequest) = WriteResult(false, null, "Operation failed: boom")
        }

        val result = ImageCommands(failing).replaceImages(request(confirm = true))

        assertFalse(result.success)
        assertContains(result.message, "FAILED")
        assertContains(result.message, "boom")
    }

    @Test
    fun `an unexpected exception from the publisher is not swallowed here`() {
        assertFailsWith<IllegalStateException> {
            ImageCommands(FakeImagePublisher(IllegalStateException("boom"))).replaceImages(request(confirm = true))
        }
    }
}
