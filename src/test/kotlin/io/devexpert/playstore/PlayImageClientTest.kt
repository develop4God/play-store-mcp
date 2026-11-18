package io.devexpert.playstore

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayImageClientTest {
    private val api = FakePlayApi()
    private val client = PlayImageClient { api.publisher }
    private val images = TestImages()

    @AfterTest
    fun cleanUp() = images.close()

    private fun request(vararg files: java.io.File, type: String = "phoneScreenshots", language: String = "en-US") =
        ReplaceStoreImagesRequest("com.example.app", language, type, files.map { it.path }, true)

    private fun shots(count: Int) = (1..count).map { images.png("s$it.png", 1080, 1920) }.toTypedArray()

    private val listingPath = "/listings/en-US/phoneScreenshots"

    /** Deletions of the edit itself, not of the images inside it. */
    private fun editDeletions() = api.requests.filter { it.method == "DELETE" && it.url.substringBefore('?').endsWith("/edits/edit-1") }

    @Test
    fun `deletes the old images first, then uploads the new ones in order`() {
        val result = client.replaceImages(request(*shots(3)))

        assertTrue(result.success)
        val relevant = api.requests.filter { it.url.contains(listingPath) && !it.url.contains("upload_id=") }
        assertEquals(listOf("DELETE", "POST", "POST", "POST"), relevant.map { it.method })
        assertContains(result.message, "[img-1, img-2, img-3]")
    }

    @Test
    fun `the commit is always held back from review`() {
        client.replaceImages(request(*shots(1)))

        val commit = api.requestsTo("POST", ":commit").single()
        assertContains(commit.url, "changesNotSentForReview=true")
    }

    @Test
    fun `uploads each file with the type of its real content`() {
        val jpeg = images.jpeg("a.jpg", 1080, 1920)
        val png = images.png("b.png", 1080, 1920)

        client.replaceImages(request(jpeg, png))

        assertEquals(listOf("image/jpeg", "image/png"), api.requests.mapNotNull { it.uploadContentType })
    }

    @Test
    fun `targets only the requested language and type`() {
        client.replaceImages(request(images.png("icon.png", 512, 512), type = "icon", language = "pt-BR"))

        assertEquals(1, api.requestsTo("DELETE", "/listings/pt-BR/icon").size)
        assertTrue(api.requests.none { it.url.contains("/listings/en-US") })
    }

    @Test
    fun `a failed upload discards the edit, so the current images stay`() {
        var uploads = 0
        api.fail = { _, url -> if (url.contains("uploadType=resumable") && ++uploads == 2) 500 else null }

        val result = client.replaceImages(request(*shots(3)))

        assertFalse(result.success)
        assertContains(result.message, "Nothing was changed in Play")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
        assertEquals(1, editDeletions().size)
    }

    @Test
    fun `a failed delete discards the edit and uploads nothing`() {
        api.fail = { method, url -> if (method == "DELETE" && url.contains(listingPath)) 500 else null }

        val result = client.replaceImages(request(*shots(2)))

        assertFalse(result.success)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
        assertTrue(api.requests.none { it.url.contains("uploadType") })
    }

    @Test
    fun `a failed commit is reported and nothing is left open`() {
        api.fail = { _, url -> if (url.contains(":commit")) 500 else null }

        val result = client.replaceImages(request(*shots(1)))

        assertFalse(result.success)
        assertEquals(1, editDeletions().size)
    }

    @Test
    fun `a failure to open the edit is reported without any write`() {
        api.fail = { method, url -> if (method == "POST" && url.endsWith("/edits")) 403 else null }

        val result = client.replaceImages(request(*shots(1)))

        assertFalse(result.success)
        assertContains(result.message, "403")
        assertTrue(api.requests.none { it.method == "DELETE" || it.url.contains("uploadType") })
    }

    @Test
    fun `a network failure during the commit does not claim that nothing changed`() {
        api.networkFailure = { _, url -> url.contains(":commit") }

        val result = client.replaceImages(request(*shots(1)))

        assertFalse(result.success)
        assertContains(result.message, "may have been applied")
        assertFalse(result.message.contains("Nothing was changed"))
    }

    @Test
    fun `a network failure before the commit still guarantees nothing changed`() {
        api.networkFailure = { _, url -> url.contains("uploadType=resumable") }

        val result = client.replaceImages(request(*shots(1)))

        assertContains(result.message, "Nothing was changed in Play")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `a failure without a message still names the problem`() {
        api.networkFailure = { _, url -> url.endsWith("/edits") }

        val result = client.replaceImages(request(*shots(1)))

        assertContains(result.message, "simulated timeout")
    }
}
