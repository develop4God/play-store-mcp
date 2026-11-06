package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeployRequestTest {
    private val tempFiles = mutableListOf<File>()

    private fun bundle(suffix: String = ".aab"): File =
        File.createTempFile("app", suffix).also { tempFiles += it }

    private fun args(
        file: File = bundle(),
        vararg overrides: Pair<String, JsonElement?>
    ): Map<String, JsonElement> {
        val base = mutableMapOf<String, JsonElement>(
            "packageName" to JsonPrimitive("com.example.app"),
            "track" to JsonPrimitive("internal"),
            "apkPath" to JsonPrimitive(file.absolutePath)
        )
        overrides.forEach { (key, value) ->
            if (value == null) base.remove(key) else base[key] = value
        }
        return base
    }

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    @Test
    fun `valid arguments default to a dry run with full rollout`() {
        val request = DeployRequest.parse(args())

        assertEquals("com.example.app", request.packageName)
        assertEquals("internal", request.track)
        assertFalse(request.confirm)
        assertTrue(request.releaseNotes.isEmpty())
    }

    @Test
    fun `alpha beta and production tracks are allowed`() {
        listOf("alpha", "beta", "production").forEach { track ->
            assertEquals(track, DeployRequest.parse(args(overrides = arrayOf("track" to JsonPrimitive(track)))).track)
        }
    }

    @Test
    fun `unknown tracks are rejected`() {
        listOf("PRODUCTION", "qa", "").forEach { track ->
            assertFailsWith<InvalidRequestException>("track '$track'") {
                DeployRequest.parse(args(overrides = arrayOf("track" to JsonPrimitive(track))))
            }
        }
    }

    @Test
    fun `missing track is rejected instead of defaulting`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("track" to null)))
        }
    }

    @Test
    fun `missing blank or malformed package name is rejected`() {
        listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("  "), JsonPrimitive("not a package")).forEach { value ->
            assertFailsWith<InvalidRequestException>("packageName '$value'") {
                DeployRequest.parse(args(overrides = arrayOf("packageName" to value)))
            }
        }
    }

    @Test
    fun `missing apkPath is rejected`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("apkPath" to null)))
        }
    }

    @Test
    fun `nonexistent file is rejected`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("apkPath" to JsonPrimitive("/no/such/app.aab"))))
        }
    }

    @Test
    fun `file that is not an aab or apk is rejected`() {
        assertFailsWith<InvalidRequestException> { DeployRequest.parse(args(bundle(".txt"))) }
    }

    @Test
    fun `confirm true is parsed and a non boolean confirm is rejected`() {
        assertTrue(DeployRequest.parse(args(overrides = arrayOf("confirm" to JsonPrimitive(true)))).confirm)
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("confirm" to JsonPrimitive("yes"))))
        }
    }

    @Test
    fun `staged rollout is rejected because testing tracks do not support it`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("rolloutPercentage" to JsonPrimitive(0.5))))
        }
    }

    @Test
    fun `constructing a request directly cannot bypass the track policy`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest("com.example.app", "qa", bundle().absolutePath, emptyMap(), true)
        }
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args()).copy(track = "staging")
        }
    }

    @Test
    fun `relative apkPath is rejected`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("apkPath" to JsonPrimitive("build/app.aab"))))
        }
    }

    @Test
    fun `json null values are rejected instead of becoming the text null`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("track" to JsonNull)))
        }
        assertFailsWith<InvalidRequestException> {
            val notes = JsonObject(mapOf("en-US" to JsonNull))
            DeployRequest.parse(args(overrides = arrayOf("releaseNotes" to notes)))
        }
    }

    @Test
    fun `release notes are parsed per language and blank text is rejected`() {
        val notes = JsonObject(mapOf("en-US" to JsonPrimitive("Fixes"), "es-ES" to JsonPrimitive("Correcciones")))
        val request = DeployRequest.parse(args(overrides = arrayOf("releaseNotes" to notes)))
        assertEquals(mapOf("en-US" to "Fixes", "es-ES" to "Correcciones"), request.releaseNotes)

        assertFailsWith<InvalidRequestException> {
            val blank = JsonObject(mapOf("en-US" to JsonPrimitive(" ")))
            DeployRequest.parse(args(overrides = arrayOf("releaseNotes" to blank)))
        }
        assertFailsWith<InvalidRequestException> {
            DeployRequest.parse(args(overrides = arrayOf("releaseNotes" to JsonPrimitive("plain string"))))
        }
    }

    // inAppUpdatePriority

    @Test
    fun `absent inAppUpdatePriority is null`() {
        assertNull(DeployRequest.parse(args()).inAppUpdatePriority)
    }

    @Test
    fun `inAppUpdatePriority accepts the bounds 0 and 5`() {
        listOf(0, 5).forEach { priority ->
            val request = DeployRequest.parse(args(overrides = arrayOf("inAppUpdatePriority" to JsonPrimitive(priority))))
            assertEquals(priority, request.inAppUpdatePriority)
        }
    }

    @Test
    fun `inAppUpdatePriority out of range is rejected`() {
        listOf(-1, 6).forEach { priority ->
            val e = assertFailsWith<InvalidRequestException>("priority $priority") {
                DeployRequest.parse(args(overrides = arrayOf("inAppUpdatePriority" to JsonPrimitive(priority))))
            }
            assertEquals("inAppUpdatePriority must be an integer from 0 to 5", e.message)
        }
    }

    @Test
    fun `inAppUpdatePriority of the wrong type is rejected`() {
        listOf(JsonPrimitive("4"), JsonPrimitive(4.5), JsonPrimitive(true), JsonNull).forEach { value ->
            val e = assertFailsWith<InvalidRequestException>("value $value") {
                DeployRequest.parse(args(overrides = arrayOf("inAppUpdatePriority" to value)))
            }
            assertEquals("inAppUpdatePriority must be an integer from 0 to 5", e.message)
        }
    }

    @Test
    fun `direct construction with an out of range priority is rejected`() {
        assertFailsWith<InvalidRequestException> {
            DeployRequest("com.example.app", "internal", bundle().absolutePath, emptyMap(), false, inAppUpdatePriority = 6)
        }
    }
}
