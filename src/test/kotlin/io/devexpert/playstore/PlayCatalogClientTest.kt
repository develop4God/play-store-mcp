package io.devexpert.playstore

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayCatalogClientTest {
    private val api = FakePlayApi()
    private val client = PlayCatalogClient { api.publisher }

    private fun listing(language: String, title: String = "App", short: String = "short", full: String = "full", video: String? = null) =
        """{"language":"$language","title":"$title","shortDescription":"$short","fullDescription":"$full"""" +
            (video?.let { ""","video":"$it"""" } ?: "") + "}"

    private fun withListings(vararg listings: String) {
        api.details = """{"defaultLanguage":"es-419","contactEmail":"a@b.c","contactWebsite":"https://x.y"}"""
        api.listings = """{"listings":[${listings.joinToString(",")}]}"""
    }

    @Test
    fun `returns the texts of the requested language with their length and limit`() {
        withListings(listing("es-419", title = "Otro"), listing("en-US", title = "Christian Devotionals", short = "Daily word", full = "Full text"))

        val view = client.getAppDetails("com.example.app", "en-US")

        assertEquals("en-US", view.language)
        assertEquals(TextField("Christian Devotionals", 21, 30), view.title)
        assertEquals(TextField("Daily word", 10, 80), view.shortDescription)
        assertEquals(TextField("Full text", 9, 4000), view.fullDescription)
    }

    @Test
    fun `counts length like the Play Console, where an emoji is two characters`() {
        withListings(listing("en-US", full = "🙏 Amen"))

        assertEquals(7, client.getAppDetails("com.example.app", "en-US").fullDescription.length)
    }

    @Test
    fun `returns the app level details and the video`() {
        withListings(listing("en-US", video = "https://youtu.be/abc"))

        val view = client.getAppDetails("com.example.app", "en-US")

        assertEquals("es-419", view.defaultLanguage)
        assertEquals("a@b.c", view.contactEmail)
        assertNull(view.contactPhone)
        assertEquals("https://x.y", view.contactWebsite)
        assertEquals("https://youtu.be/abc", view.video)
    }

    @Test
    fun `reports the images of every supported type with count and limit`() {
        withListings(listing("en-US"))
        api.images["en-US/icon"] = """{"images":[{"id":"i1","url":"https://img/1","sha256":"aa"}]}"""
        api.images["en-US/phoneScreenshots"] = """{"images":[{"id":"p1"},{"id":"p2"},{"id":"p3"}]}"""

        val images = client.getAppDetails("com.example.app", "en-US").images

        assertEquals(StoreImageSlots.maxPerType.keys.toList(), images.keys.toList())
        assertEquals(ImageGroup(1, 1, listOf(StoreImage("i1", "https://img/1", "aa"))), images["icon"])
        assertEquals(listOf("p1", "p2", "p3"), images.getValue("phoneScreenshots").images.map { it.id })
        assertEquals(3, images.getValue("phoneScreenshots").count)
        assertEquals(8, images.getValue("phoneScreenshots").max)
    }

    @Test
    fun `a type without images is an empty group, not an error`() {
        withListings(listing("en-US"))

        val group = client.getAppDetails("com.example.app", "en-US").images.getValue("featureGraphic")

        assertEquals(ImageGroup(0, 1, emptyList()), group)
    }

    @Test
    fun `an unknown language fails and lists the available ones`() {
        withListings(listing("es-419"), listing("en-US"))

        val error = assertFailsWith<PlayStoreException> { client.getAppDetails("com.example.app", "en") }

        assertContains(error.message.orEmpty(), "No store listing for 'en'")
        assertContains(error.message.orEmpty(), "[en-US, es-419]")
    }

    @Test
    fun `an app without listings fails for any language`() {
        api.details = "{}"
        api.listings = "{}"

        assertFailsWith<PlayStoreException> { client.getAppDetails("com.example.app", "en-US") }
    }

    @Test
    fun `a Play HTTP error surfaces`() {
        api.fail = { _, url -> if (url.endsWith("/details")) 403 else null }

        val error = assertFailsWith<Exception> { client.getAppDetails("com.example.app", "en-US") }

        assertContains(error.message.orEmpty(), "403")
    }

    @Test
    fun `never commits and always deletes the edit, on success and on failure`() {
        withListings(listing("en-US"))
        client.getAppDetails("com.example.app", "en-US")
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)

        assertFailsWith<PlayStoreException> { client.getAppDetails("com.example.app", "xx") }

        assertEquals(2, api.requestsTo("DELETE", "/edits/edit-1").size)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }
}
