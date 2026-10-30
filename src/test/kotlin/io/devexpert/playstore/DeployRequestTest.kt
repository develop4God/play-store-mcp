package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(1.0, request.rolloutPercentage)
        assertTrue(request.releaseNotes.isEmpty())
    }

    @Test
    fun `alpha track is allowed`() {
        assertEquals("alpha", DeployRequest.parse(args(overrides = arrayOf("track" to JsonPrimitive("alpha")))).track)
    }

    @Test
    fun `production beta and unknown tracks are rejected`() {
        listOf("production", "beta", "PRODUCTION", "qa", "").forEach { track ->
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
    fun `rollout outside 0 exclusive to 1 inclusive is rejected`() {
        listOf(0.0, -0.1, 1.1).forEach { value ->
            assertFailsWith<InvalidRequestException>("rollout $value") {
                DeployRequest.parse(args(overrides = arrayOf("rolloutPercentage" to JsonPrimitive(value))))
            }
        }
        assertEquals(
            0.5,
            DeployRequest.parse(args(overrides = arrayOf("rolloutPercentage" to JsonPrimitive(0.5)))).rolloutPercentage
        )
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
}
