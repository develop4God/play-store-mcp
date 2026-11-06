package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseNotesLengthTest {
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = tempFiles.forEach { it.delete() }

    private fun notes(vararg perLanguage: Pair<String, String>): JsonObject =
        JsonObject(perLanguage.associate { (language, text) -> language to JsonPrimitive(text) })

    private fun deploy(releaseNotes: JsonObject): DeployRequest {
        val file = File.createTempFile("app", ".aab").also { tempFiles += it }
        val args: Map<String, JsonElement> = mapOf(
            "packageName" to JsonPrimitive("com.example.app"),
            "track" to JsonPrimitive("beta"),
            "apkPath" to JsonPrimitive(file.absolutePath),
            "releaseNotes" to releaseNotes,
        )
        return DeployRequest.parse(args)
    }

    private fun update(releaseNotes: JsonObject): UpdateReleaseNotesRequest {
        val args: Map<String, JsonElement> = mapOf(
            "packageName" to JsonPrimitive("com.example.app"),
            "track" to JsonPrimitive("beta"),
            "versionCode" to JsonPrimitive(144),
            "releaseNotes" to releaseNotes,
        )
        return UpdateReleaseNotesRequest.parse(args)
    }

    @Test
    fun `a note of exactly the Play limit is accepted`() {
        val text = "a".repeat(MAX_RELEASE_NOTE_CHARACTERS)

        assertEquals(text, deploy(notes("en-US" to text)).releaseNotes.getValue("en-US"))
        assertEquals(text, update(notes("en-US" to text)).releaseNotes.getValue("en-US"))
    }

    @Test
    fun `a note over the Play limit is rejected on deploy and on update`() {
        val text = "a".repeat(MAX_RELEASE_NOTE_CHARACTERS + 1)

        assertFailsWith<InvalidRequestException> { deploy(notes("en-US" to text)) }
        assertFailsWith<InvalidRequestException> { update(notes("en-US" to text)) }
    }

    @Test
    fun `the error names the language and both lengths`() {
        val error = assertFailsWith<InvalidRequestException> {
            update(notes("en-US" to "ok", "fr-FR" to "a".repeat(534)))
        }

        assertContains(error.message.orEmpty(), "fr-FR")
        assertContains(error.message.orEmpty(), "534")
        assertContains(error.message.orEmpty(), "$MAX_RELEASE_NOTE_CHARACTERS")
    }

    @Test
    fun `the limit applies to each language on its own`() {
        val atLimit = "a".repeat(MAX_RELEASE_NOTE_CHARACTERS)

        assertEquals(3, update(notes("en-US" to atLimit, "es-ES" to atLimit, "fr-FR" to atLimit)).releaseNotes.size)
    }

    @Test
    fun `an emoji counts as one character`() {
        val atLimit = "🚀".repeat(MAX_RELEASE_NOTE_CHARACTERS)

        assertEquals(atLimit, update(notes("en-US" to atLimit)).releaseNotes.getValue("en-US"))
        assertFailsWith<InvalidRequestException> {
            update(notes("en-US" to atLimit + "🚀"))
        }
    }

    @Test
    fun `an over limit note is rejected before anything could be sent`() {
        val request = assertFailsWith<InvalidRequestException> {
            deploy(notes("en-US" to "a".repeat(700)))
        }

        assertContains(request.message.orEmpty(), "700")
    }
}
