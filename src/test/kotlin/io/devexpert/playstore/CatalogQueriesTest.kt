package io.devexpert.playstore

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogQueriesTest {
    private val catalog = FakeCatalog()
    private val queries = CatalogQueries(catalog)

    @Test
    fun `each query renders its data as JSON`() {
        assertContains(queries.appDetails("com.example.app").message, "dev@example.com")
        assertContains(queries.storeListings("com.example.app").message, "My App")
        assertContains(queries.inAppProducts("com.example.app").message, "coffee")
        assertContains(queries.subscriptions("com.example.app").message, "monthly")
    }

    @Test
    fun `a query is executed and successful`() {
        val result = queries.appDetails("com.example.app")

        assertTrue(result.executed)
        assertTrue(result.success)
    }

    @Test
    fun `an invalid package name is rejected before reaching Play`() {
        assertFailsWith<InvalidRequestException> { queries.inAppProducts("not a package") }
        assertFailsWith<InvalidRequestException> { queries.appDetails("") }
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun `a Play failure becomes a failed result`() {
        val result = CatalogQueries(FakeCatalog(IllegalStateException("boom"))).subscriptions("com.example.app")

        assertFalse(result.success)
        assertEquals("Operation failed: boom", result.message)
    }
}
