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

    @Test
    fun `getAppDetails maps the fields and deletes the edit`() {
        api.details = """{"defaultLanguage":"en-US","contactEmail":"a@b.c","contactWebsite":"https://x.y"}"""

        val details = client.getAppDetails("com.example.app")

        assertEquals(AppDetailsInfo("en-US", "a@b.c", null, "https://x.y"), details)
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
        assertTrue(api.requestsTo("POST", ":commit").isEmpty())
    }

    @Test
    fun `getAppDetails still deletes the edit when Play fails`() {
        api.details = null

        assertFailsWith<Exception> { client.getAppDetails("com.example.app") }
        assertEquals(1, api.requestsTo("DELETE", "/edits/edit-1").size)
    }

    @Test
    fun `listStoreListings returns every language`() {
        api.listings = """{"listings":[
            {"language":"en-US","title":"App","shortDescription":"short","fullDescription":"full"},
            {"language":"es-ES","title":"Aplicacion"}]}"""

        val listings = client.listStoreListings("com.example.app")

        assertEquals(listOf("en-US", "es-ES"), listings.map { it.language })
        assertEquals("short", listings[0].shortDescription)
        assertNull(listings[1].fullDescription)
    }

    @Test
    fun `listStoreListings is empty when the app has none`() {
        api.listings = "{}"

        assertTrue(client.listStoreListings("com.example.app").isEmpty())
    }

    @Test
    fun `listInAppProducts follows pagination across pages`() {
        api.pages["inappproducts"] = listOf(
            """{"inappproduct":[{"sku":"coffee","status":"active","purchaseType":"managedUser","defaultLanguage":"en-US",
                "defaultPrice":{"currency":"USD","priceMicros":"990000"},
                "listings":{"en-US":{"title":"Coffee","description":"A coffee"}}}],
                "tokenPagination":{"nextPageToken":"1"}}""",
            """{"inappproduct":[{"sku":"tea"}]}""",
        )

        val products = client.listInAppProducts("com.example.app")

        assertEquals(listOf("coffee", "tea"), products.map { it.sku })
        assertEquals(PriceInfo("USD", "990000"), products[0].defaultPrice)
        assertEquals(ListingText("en-US", "Coffee", "A coffee"), products[0].listings.single())
        assertNull(products[1].defaultPrice)
        assertTrue(products[1].listings.isEmpty())
    }

    @Test
    fun `listInAppProducts is empty when the app has no products`() {
        api.pages["inappproducts"] = listOf("{}")

        assertTrue(client.listInAppProducts("com.example.app").isEmpty())
    }

    @Test
    fun `listInAppProducts surfaces an HTTP error`() {
        api.fail = { _, url -> if (url.contains("inappproducts")) 403 else null }

        val error = assertFailsWith<Exception> { client.listInAppProducts("com.example.app") }
        assertContains(error.message.orEmpty(), "403")
    }

    @Test
    fun `listSubscriptions follows pagination and maps base plans`() {
        api.pages["subscriptions"] = listOf(
            """{"subscriptions":[{"productId":"pro","basePlans":[{"basePlanId":"monthly","state":"ACTIVE"}],
                "listings":[{"languageCode":"en-US","title":"Pro","description":"All features"}]}],
                "nextPageToken":"1"}""",
            """{"subscriptions":[{"productId":"plus"}]}""",
        )

        val subscriptions = client.listSubscriptions("com.example.app")

        assertEquals(listOf("pro", "plus"), subscriptions.map { it.productId })
        assertEquals(BasePlanInfo("monthly", "ACTIVE"), subscriptions[0].basePlans.single())
        assertEquals(ListingText("en-US", "Pro", "All features"), subscriptions[0].listings.single())
        assertTrue(subscriptions[1].basePlans.isEmpty())
    }

    @Test
    fun `listSubscriptions is empty when there are none`() {
        api.pages["subscriptions"] = listOf("{}")

        assertTrue(client.listSubscriptions("com.example.app").isEmpty())
    }
}
