package io.devexpert.playstore

import java.io.File
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

    private fun slot(language: String, type: String, vararg files: File) = ImageReplacement(language, type, files.map { it.path })

    private fun request(vararg slots: ImageReplacement, sendForReview: Boolean = false) =
        ReplaceStoreImagesRequest("com.example.app", slots.toList(), true, sendForReview)

    private fun shots(count: Int) = (1..count).map { images.png("s$it.png", 1080, 1920) }.toTypedArray()

    private fun phone(language: String = "en-US", count: Int = 1) = slot(language, "phoneScreenshots", *shots(count))

    /** Deletions of the edit itself, not of the images inside it. */
    private fun editDeletions() = api.requests.filter { it.method == "DELETE" && it.url.substringBefore('?').endsWith("/edits/edit-1") }

    private fun commit() = api.requestsTo("POST", ":commit").single().url

    @Test
    fun `deletes the old images first, then uploads the new ones in order`() {
        val result = client.replaceImages(request(phone(count = 3)))

        assertTrue(result.success)
        val relevant = api.requests.filter { it.url.contains("/listings/en-US/phoneScreenshots") && !it.url.contains("upload_id=") }
        assertEquals(listOf("DELETE", "POST", "POST", "POST"), relevant.map { it.method })
        assertContains(result.message, "[img-1, img-2, img-3]")
    }

    @Test
    fun `several languages go through one edit and one commit`() {
        val result = client.replaceImages(request(phone("en-US", 2), phone("es-419", 2), slot("pt-BR", "icon", images.png("i.png", 512, 512))))

        assertTrue(result.success)
        assertEquals(1, api.requestsTo("POST", "/edits").filter { it.url.endsWith("/edits") }.size)
        assertEquals(1, api.requestsTo("POST", ":commit").size)
        assertEquals(1, api.requestsTo("DELETE", "/listings/en-US/phoneScreenshots").size)
        assertEquals(1, api.requestsTo("DELETE", "/listings/es-419/phoneScreenshots").size)
        assertEquals(1, api.requestsTo("DELETE", "/listings/pt-BR/icon").size)
        assertContains(result.message, "en-US phoneScreenshots")
        assertContains(result.message, "pt-BR icon")
    }

    @Test
    fun `by default the commit is held back from review`() {
        client.replaceImages(request(phone()))

        assertContains(commit(), "changesNotSentForReview=true")
        assertFalse(commit().contains("changesInReviewBehavior"))
    }

    @Test
    fun `sendForReview commits without the hold and never cancels a review in progress`() {
        val result = client.replaceImages(request(phone(), sendForReview = true))

        assertTrue(result.success)
        assertFalse(commit().contains("changesNotSentForReview"))
        assertContains(commit(), "changesInReviewBehavior=ERROR_IF_IN_REVIEW")
        assertContains(result.message, "SENT TO GOOGLE REVIEW")
    }

    @Test
    fun `a rejected hold is never retried without it, and the error says how to opt in`() {
        api.errorMessage = "Changes are sent for review automatically. The query parameter changesNotSentForReview must not be set."
        api.fail = { _, url -> if (url.contains(":commit") && url.contains("changesNotSentForReview")) 400 else null }

        val result = client.replaceImages(request(phone()))

        assertFalse(result.success)
        assertEquals(1, api.requestsTo("POST", ":commit").size)
        assertEquals(1, editDeletions().size)
        assertContains(result.message, "sendForReview=true")
    }

    @Test
    fun `a review already in progress fails the send and changes nothing`() {
        api.fail = { _, url -> if (url.contains(":commit") && url.contains("ERROR_IF_IN_REVIEW")) 409 else null }

        val result = client.replaceImages(request(phone(), sendForReview = true))

        assertFalse(result.success)
        assertContains(result.message, "Nothing was changed in Play")
        assertEquals(1, editDeletions().size)
    }

    @Test
    fun `uploads each file with the type of its real content`() {
        client.replaceImages(request(slot("en-US", "phoneScreenshots", images.jpeg("a.jpg", 1080, 1920), images.png("b.png", 1080, 1920))))

        assertEquals(listOf("image/jpeg", "image/png"), api.requests.mapNotNull { it.uploadContentType })
    }

    @Test
    fun `targets only the requested slots`() {
        client.replaceImages(request(slot("pt-BR", "icon", images.png("icon.png", 512, 512))))

        assertEquals(1, api.requestsTo("DELETE", "/listings/pt-BR/icon").size)
        assertTrue(api.requests.none { it.url.contains("/listings/en-US") })
    }

    @Test
    fun `a failed upload in a later slot discards everything`() {
        var uploads = 0
        api.fail = { _, url -> if (url.contains("uploadType=resumable") && ++uploads == 3) 500 else null }

        val result = client.replaceImages(request(phone("en-US", 2), phone("es-419", 2)))

        assertFalse(result.success)
        assertContains(result.message, "Nothing was changed in Play")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
        assertEquals(1, editDeletions().size)
    }

    @Test
    fun `a failed delete discards the edit and uploads nothing`() {
        api.fail = { method, url -> if (method == "DELETE" && url.contains("/listings/en-US/phoneScreenshots")) 500 else null }

        val result = client.replaceImages(request(phone(count = 2)))

        assertFalse(result.success)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
        assertTrue(api.requests.none { it.url.contains("uploadType") })
    }

    @Test
    fun `a failed commit is reported and nothing is left open`() {
        api.fail = { _, url -> if (url.contains(":commit")) 500 else null }

        val result = client.replaceImages(request(phone()))

        assertFalse(result.success)
        assertEquals(1, editDeletions().size)
    }

    @Test
    fun `a failure to open the edit is reported without any write`() {
        api.fail = { method, url -> if (method == "POST" && url.endsWith("/edits")) 403 else null }

        val result = client.replaceImages(request(phone()))

        assertFalse(result.success)
        assertContains(result.message, "403")
        assertTrue(api.requests.none { it.method == "DELETE" || it.url.contains("uploadType") })
    }

    @Test
    fun `a network failure during the commit does not claim that nothing changed`() {
        api.networkFailure = { _, url -> url.contains(":commit") }

        val result = client.replaceImages(request(phone()))

        assertFalse(result.success)
        assertContains(result.message, "may have been applied")
        assertFalse(result.message.contains("Nothing was changed"))
    }

    @Test
    fun `a network failure before the commit still guarantees nothing changed`() {
        api.networkFailure = { _, url -> url.contains("uploadType=resumable") }

        val result = client.replaceImages(request(phone()))

        assertContains(result.message, "Nothing was changed in Play")
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `a failure without a message still names the problem`() {
        api.networkFailure = { _, url -> url.endsWith("/edits") }

        assertContains(client.replaceImages(request(phone())).message, "simulated timeout")
    }
}
