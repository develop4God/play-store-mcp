package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PromoteRequestTest {
    private fun args(vararg overrides: Pair<String, JsonElement?>): Map<String, JsonElement> {
        val base = mutableMapOf<String, JsonElement>(
            "packageName" to JsonPrimitive("com.example.app"),
            "fromTrack" to JsonPrimitive("internal"),
            "toTrack" to JsonPrimitive("alpha"),
            "versionCode" to JsonPrimitive(141)
        )
        overrides.forEach { (key, value) ->
            if (value == null) base.remove(key) else base[key] = value
        }
        return base
    }

    @Test
    fun `internal to alpha is allowed and defaults to a dry run`() {
        val request = PromoteRequest.parse(args())

        assertEquals("internal", request.fromTrack)
        assertEquals("alpha", request.toTrack)
        assertEquals(141L, request.versionCode)
        assertFalse(request.confirm)
    }

    @Test
    fun `promoting to production or beta is rejected`() {
        listOf("production", "beta").forEach { target ->
            assertFailsWith<InvalidRequestException>("toTrack '$target'") {
                PromoteRequest.parse(args("toTrack" to JsonPrimitive(target)))
            }
        }
    }

    @Test
    fun `promoting from production is rejected`() {
        assertFailsWith<InvalidRequestException> {
            PromoteRequest.parse(args("fromTrack" to JsonPrimitive("production")))
        }
    }

    @Test
    fun `same source and target track is rejected`() {
        assertFailsWith<InvalidRequestException> {
            PromoteRequest.parse(args("fromTrack" to JsonPrimitive("alpha")))
        }
    }

    @Test
    fun `missing tracks are rejected instead of defaulting`() {
        listOf("fromTrack", "toTrack").forEach { key ->
            assertFailsWith<InvalidRequestException>(key) { PromoteRequest.parse(args(key to null)) }
        }
    }

    @Test
    fun `missing zero negative or non numeric version code is rejected`() {
        listOf<JsonElement?>(null, JsonPrimitive(0), JsonPrimitive(-5), JsonPrimitive("abc")).forEach { value ->
            assertFailsWith<InvalidRequestException>("versionCode '$value'") {
                PromoteRequest.parse(args("versionCode" to value))
            }
        }
    }

    @Test
    fun `malformed package name is rejected`() {
        assertFailsWith<InvalidRequestException> {
            PromoteRequest.parse(args("packageName" to JsonPrimitive("bad name")))
        }
    }
}
