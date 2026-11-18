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
    fun `renders the details as JSON for the requested language`() {
        val result = queries.appDetails("com.example.app", "en-US")

        assertTrue(result.executed)
        assertTrue(result.success)
        assertContains(result.message, "\"language\": \"en-US\"")
        assertContains(result.message, "\"max\": 30")
        assertEquals(listOf("com.example.app" to "en-US"), catalog.calls)
    }

    @Test
    fun `rejects an invalid package name or language before reaching Play`() {
        assertFailsWith<InvalidRequestException> { queries.appDetails("not a package", "en-US") }
        assertFailsWith<InvalidRequestException> { queries.appDetails("com.example.app", "") }
        assertFailsWith<InvalidRequestException> { queries.appDetails("com.example.app", "english US") }
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun `a Play failure becomes a failed result`() {
        val result = CatalogQueries(FakeCatalog(IllegalStateException("boom"))).appDetails("com.example.app", "en-US")

        assertFalse(result.success)
        assertEquals("Operation failed: boom", result.message)
    }
}
