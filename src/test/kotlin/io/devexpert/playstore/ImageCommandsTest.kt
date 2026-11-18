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

    private fun request(confirm: Boolean) = ReplaceStoreImagesRequest(
        "com.example.app",
        "en-US",
        "phoneScreenshots",
        listOf(images.png("one.png", 1080, 1920).path, images.png("two.png", 1080, 1920).path),
        confirm
    )

    @Test
    fun `a dry run describes the plan and never reaches Play`() {
        val result = commands.replaceImages(request(confirm = false))

        assertFalse(result.executed)
        assertTrue(result.success)
        assertContains(result.message, "DRY RUN")
        assertContains(result.message, "delete the current phoneScreenshots of en-US")
        assertContains(result.message, "one.png (1080x1920")
        assertContains(result.message, "NOT sent for review")
        assertTrue(publisher.calls.isEmpty())
    }

    @Test
    fun `a dry run lists the files in upload order`() {
        val message = commands.replaceImages(request(confirm = false)).message

        assertTrue(message.indexOf("one.png") < message.indexOf("two.png"))
    }

    @Test
    fun `confirm sends exactly one request to Play`() {
        val result = commands.replaceImages(request(confirm = true))

        assertTrue(result.executed)
        assertTrue(result.success)
        assertContains(result.message, "SUCCESS")
        assertEquals(1, publisher.calls.size)
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
