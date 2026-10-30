package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UpdateReleaseNotesRequestTest {
    private fun args(vararg overrides: Pair<String, JsonElement?>): Map<String, JsonElement> {
        val base = mutableMapOf<String, JsonElement>(
            "packageName" to JsonPrimitive("com.example.app"),
            "track" to JsonPrimitive("beta"),
            "versionCode" to JsonPrimitive(141),
            "releaseNotes" to JsonObject(mapOf("en-US" to JsonPrimitive("Fixes")))
        )
        overrides.forEach { (key, value) -> if (value == null) base.remove(key) else base[key] = value }
        return base
    }

    @Test
    fun `valid request defaults to a dry run`() {
        val request = UpdateReleaseNotesRequest.parse(args())

        assertEquals("beta", request.track)
        assertEquals(141L, request.versionCode)
        assertEquals(mapOf("en-US" to "Fixes"), request.releaseNotes)
        assertFalse(request.confirm)
    }

    @Test
    fun `production is allowed but unknown tracks are rejected`() {
        assertEquals("production", UpdateReleaseNotesRequest.parse(args("track" to JsonPrimitive("production"))).track)
        listOf("qa", "").forEach { track ->
            assertFailsWith<InvalidRequestException>("track '$track'") {
                UpdateReleaseNotesRequest.parse(args("track" to JsonPrimitive(track)))
            }
        }
    }

    @Test
    fun `missing release notes are reported as required`() {
        val error = assertFailsWith<InvalidRequestException> {
            UpdateReleaseNotesRequest.parse(args("releaseNotes" to null))
        }
        assertEquals("releaseNotes is required", error.message)
    }

    @Test
    fun `empty release notes are rejected`() {
        assertFailsWith<InvalidRequestException> {
            UpdateReleaseNotesRequest.parse(args("releaseNotes" to JsonObject(emptyMap())))
        }
    }

    @Test
    fun `blank note text is rejected`() {
        assertFailsWith<InvalidRequestException> {
            UpdateReleaseNotesRequest.parse(args("releaseNotes" to JsonObject(mapOf("en-US" to JsonPrimitive(" ")))))
        }
    }

    @Test
    fun `invalid version code is rejected`() {
        listOf<JsonElement?>(null, JsonPrimitive(0), JsonPrimitive("abc")).forEach { value ->
            assertFailsWith<InvalidRequestException>("versionCode '$value'") {
                UpdateReleaseNotesRequest.parse(args("versionCode" to value))
            }
        }
    }

    @Test
    fun `constructing directly cannot bypass the track policy`() {
        assertFailsWith<InvalidRequestException> {
            UpdateReleaseNotesRequest("com.example.app", "qa", 141, mapOf("en-US" to "x"), true)
        }
    }
}
