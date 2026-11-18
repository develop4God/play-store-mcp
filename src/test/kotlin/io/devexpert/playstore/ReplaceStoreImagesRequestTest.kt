package io.devexpert.playstore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplaceStoreImagesRequestTest {
    private val images = TestImages()

    @AfterTest
    fun cleanUp() = images.close()

    private fun shot(name: String) = images.png(name, 1080, 1920).path

    private fun entry(language: String, type: String, vararg paths: String): JsonObject = buildJsonObject {
        put("language", language)
        put("imageType", type)
        put("imagePaths", buildJsonArray { paths.forEach { add(it) } })
    }

    private fun args(vararg entries: JsonElement, extra: Map<String, JsonElement> = emptyMap()) =
        mapOf<String, JsonElement>(
            "packageName" to JsonPrimitive("com.example.app"),
            "replacements" to JsonArray(entries.toList())
        ) + extra

    private fun rejection(args: Map<String, JsonElement>) =
        assertFailsWith<InvalidRequestException> { ReplaceStoreImagesRequest.parse(args) }.message.orEmpty()

    @Test
    fun `parses several languages and keeps the file order`() {
        val request = ReplaceStoreImagesRequest.parse(
            args(
                entry("en-US", "phoneScreenshots", shot("b.png"), shot("a.png")),
                entry("es-419", "phoneScreenshots", shot("c.png"))
            )
        )

        assertEquals(listOf("en-US", "es-419"), request.replacements.map { it.language })
        assertEquals(listOf("b.png", "a.png"), request.replacements[0].imagePaths.map { File(it).name })
        assertFalse(request.confirm)
        assertFalse(request.sendForReview)
    }

    @Test
    fun `confirm and sendForReview must be real booleans`() {
        val ok = args(entry("en-US", "phoneScreenshots", shot("a.png")))

        val both = ReplaceStoreImagesRequest.parse(ok + mapOf("confirm" to JsonPrimitive(true), "sendForReview" to JsonPrimitive(true)))
        assertTrue(both.confirm && both.sendForReview)
        assertContains(rejection(ok + ("confirm" to JsonPrimitive("true"))), "confirm must be a boolean")
        assertContains(rejection(ok + ("sendForReview" to JsonPrimitive("true"))), "sendForReview must be a boolean")
        assertContains(rejection(ok + ("sendForReview" to JsonPrimitive(1))), "sendForReview must be a boolean")
    }

    @Test
    fun `sendForReview is off unless asked for`() {
        val request = ReplaceStoreImagesRequest.parse(args(entry("en-US", "phoneScreenshots", shot("a.png"))) + ("confirm" to JsonPrimitive(true)))

        assertFalse(request.sendForReview)
    }

    @Test
    fun `a directly built request does not send for review unless told to`() {
        val replacement = ImageReplacement("en-US", "phoneScreenshots", listOf(shot("a.png")))

        assertFalse(ReplaceStoreImagesRequest("com.example.app", listOf(replacement), confirm = true).sendForReview)
    }

    @Test
    fun `rejects missing or malformed replacements`() {
        assertContains(rejection(args() - "replacements"), "replacements must be an array")
        assertContains(rejection(args()), "at least one entry")
        assertContains(rejection(args(JsonPrimitive("x"))), "must be an object")
        assertContains(rejection(args(buildJsonObject { put("language", "en-US") })), "imageType is required")
        assertContains(rejection(args(buildJsonObject { put("language", "en-US"); put("imageType", "icon") })), "imagePaths must be an array")
    }

    @Test
    fun `rejects an invalid package, language or image type`() {
        val good = entry("en-US", "phoneScreenshots", shot("a.png"))
        assertFailsWith<InvalidRequestException> { ReplaceStoreImagesRequest.parse(args(good) + ("packageName" to JsonPrimitive("bad name"))) }
        assertContains(rejection(args(entry("english", "phoneScreenshots", shot("a.png")))), "language must be")
        assertContains(rejection(args(entry("en-US", "tvBanner", shot("a.png")))), "imageType must be one of")
    }

    @Test
    fun `rejects the same language and type twice`() {
        val one = entry("en-US", "phoneScreenshots", shot("a.png"))
        val two = entry("en-US", "phoneScreenshots", shot("b.png"))

        assertContains(rejection(args(one, two)), "only once")
    }

    @Test
    fun `the same language with another type is fine`() {
        val request = ReplaceStoreImagesRequest.parse(
            args(
                entry("en-US", "phoneScreenshots", shot("a.png")),
                entry("en-US", "icon", images.png("i.png", 512, 512).path)
            )
        )

        assertEquals(2, request.replacements.size)
    }

    @Test
    fun `enforces the number of images each type takes`() {
        val nine = (1..9).map { shot("s$it.png") }.toTypedArray()
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", *nine))), "at most 8")
        val icons = arrayOf(images.png("i1.png", 512, 512).path, images.png("i2.png", 512, 512).path)
        assertContains(rejection(args(entry("en-US", "icon", *icons))), "at most 1")
        assertContains(rejection(args(entry("en-US", "phoneScreenshots"))), "at least one image")
        assertEquals(8, ReplaceStoreImagesRequest.parse(args(entry("en-US", "phoneScreenshots", *nine.take(8).toTypedArray()))).replacements[0].images.size)
    }

    @Test
    fun `rejects a repeated file, even when spelled differently`() {
        val one = shot("a.png")
        val viaDots = File(File(one).parentFile, "../${File(one).parentFile.name}/a.png").path

        assertContains(rejection(args(entry("en-US", "phoneScreenshots", one, one))), "must not repeat")
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", one, viaDots))), "must not repeat")
    }

    @Test
    fun `rejects relative, missing and wrongly named files`() {
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", "shot.png"))), "absolute")
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", images.file("missing.png").path))), "file not found")
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", images.text("notes.txt").path))), ".png, .jpg or .jpeg")
    }

    @Test
    fun `rejects a file that breaks a Play image rule`() {
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", images.png("small.png", 100, 100).path))), "320-3840")
        assertContains(rejection(args(entry("en-US", "phoneScreenshots", images.text("fake.png").path))), "not a PNG or JPEG")
    }

    @Test
    fun `a bad file in a later entry rejects the whole request`() {
        val good = entry("en-US", "phoneScreenshots", shot("a.png"))
        val bad = entry("es-419", "phoneScreenshots", images.text("fake.png").path)

        assertContains(rejection(args(good, bad)), "not a PNG or JPEG")
    }

    @Test
    fun `the rules cannot be bypassed by constructing or copying directly`() {
        val replacement = ImageReplacement("en-US", "phoneScreenshots", listOf(shot("a.png")))

        assertFailsWith<InvalidRequestException> { replacement.copy(imagePaths = listOf(images.text("x.png").path)) }
        assertFailsWith<InvalidRequestException> { replacement.copy(imageType = "icon") }
        assertFailsWith<InvalidRequestException> { replacement.copy(language = "") }
        val twice = listOf(replacement, replacement)
        assertFailsWith<InvalidRequestException> { ReplaceStoreImagesRequest("com.example.app", twice, false) }
        val dotted = File(File(replacement.imagePaths[0]).parentFile, "./a.png").path
        assertFailsWith<InvalidRequestException> { replacement.copy(imagePaths = listOf(replacement.imagePaths[0], dotted)) }
    }
}
