package io.devexpert.playstore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
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

    private fun args(paths: JsonElement, type: String = "phoneScreenshots", extra: Map<String, JsonElement> = emptyMap()) =
        mapOf<String, JsonElement>(
            "packageName" to JsonPrimitive("com.example.app"),
            "language" to JsonPrimitive("en-US"),
            "imageType" to JsonPrimitive(type),
            "imagePaths" to paths
        ) + extra

    private fun paths(vararg p: String): JsonArray = buildJsonArray { p.forEach { add(it) } }

    private fun rejection(args: Map<String, JsonElement>) =
        assertFailsWith<InvalidRequestException> { ReplaceStoreImagesRequest.parse(args) }.message.orEmpty()

    @Test
    fun `parses a valid request and keeps the file order`() {
        val request = ReplaceStoreImagesRequest.parse(args(paths(shot("b.png"), shot("a.png"))))

        assertEquals(listOf("b.png", "a.png"), request.imagePaths.map { File(it).name })
        assertEquals(2, request.images.size)
        assertFalse(request.confirm)
    }

    @Test
    fun `confirm must be a real boolean`() {
        assertTrue(ReplaceStoreImagesRequest.parse(args(paths(shot("a.png")), extra = mapOf("confirm" to JsonPrimitive(true)))).confirm)
        assertContains(rejection(args(paths(shot("a.png")), extra = mapOf("confirm" to JsonPrimitive("true")))), "confirm must be a boolean")
    }

    @Test
    fun `rejects missing or malformed arguments`() {
        assertContains(rejection(args(paths(shot("a.png"))) - "imagePaths"), "imagePaths must be an array")
        assertContains(rejection(args(JsonPrimitive("/x.png"))), "imagePaths must be an array")
        assertContains(rejection(args(paths())), "at least one image")
        assertContains(rejection(args(buildJsonArray { add(1) })), "non-blank path")
        assertContains(rejection(args(paths(""))), "non-blank path")
        assertContains(rejection(args(paths(shot("a.png"))) - "language"), "language is required")
    }

    @Test
    fun `rejects an invalid package, language or image type`() {
        assertFailsWith<InvalidRequestException> {
            ReplaceStoreImagesRequest.parse(args(paths(shot("a.png"))) + ("packageName" to JsonPrimitive("bad name")))
        }
        assertContains(rejection(args(paths(shot("a.png"))) + ("language" to JsonPrimitive("english"))), "language must be")
        assertContains(rejection(args(paths(shot("a.png")), type = "tvBanner")), "imageType must be one of")
    }

    @Test
    fun `enforces the number of images each type takes`() {
        val nine = (1..9).map { shot("s$it.png") }
        assertContains(rejection(args(paths(*nine.toTypedArray()))), "at most 8")
        val icons = arrayOf(images.png("i1.png", 512, 512).path, images.png("i2.png", 512, 512).path)
        assertContains(rejection(args(paths(*icons), type = "icon")), "at most 1")
        assertEquals(8, ReplaceStoreImagesRequest.parse(args(paths(*nine.take(8).toTypedArray()))).images.size)
    }

    @Test
    fun `rejects a repeated file, even when spelled differently`() {
        val one = shot("a.png")
        assertContains(rejection(args(paths(one, one))), "must not repeat")
        val viaDots = File(File(one).parentFile, "../${File(one).parentFile.name}/a.png").path
        assertContains(rejection(args(paths(one, viaDots))), "must not repeat")
    }

    @Test
    fun `rejects relative, missing and wrongly named files`() {
        assertContains(rejection(args(paths("shot.png"))), "absolute")
        assertContains(rejection(args(paths(images.file("missing.png").path))), "file not found")
        assertContains(rejection(args(paths(images.text("notes.txt").path))), ".png, .jpg or .jpeg")
        assertContains(rejection(args(paths(images.file("").parent))), "file not found")
    }

    @Test
    fun `rejects a file that breaks a Play image rule`() {
        assertContains(rejection(args(paths(images.png("small.png", 100, 100).path))), "320-3840")
        assertContains(rejection(args(paths(images.text("fake.png").path))), "not a PNG or JPEG")
    }

    @Test
    fun `the rules cannot be bypassed by constructing or copying the request directly`() {
        val good = ReplaceStoreImagesRequest.parse(args(paths(shot("a.png"))))

        assertFailsWith<InvalidRequestException> { good.copy(imagePaths = listOf(images.text("x.png").path)) }
        assertFailsWith<InvalidRequestException> { good.copy(imageType = "icon") }
        assertFailsWith<InvalidRequestException> { good.copy(language = "") }
    }

    @Test
    fun `a directly built request rejects the same file spelled two ways`() {
        val one = shot("a.png")
        val viaDots = File(File(one).parentFile, "./a.png").path

        assertFailsWith<InvalidRequestException> { ReplaceStoreImagesRequest("com.example.app", "en-US", "phoneScreenshots", listOf(one, viaDots), false) }
    }
}
