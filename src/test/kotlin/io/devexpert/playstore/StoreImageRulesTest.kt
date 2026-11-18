package io.devexpert.playstore

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreImageRulesTest {
    private val images = TestImages()

    @AfterTest
    fun cleanUp() = images.close()

    private fun rejection(file: java.io.File, type: String) = assertFailsWith<InvalidRequestException> {
        StoreImageRules.validate(file, type)
    }.message.orEmpty()

    @Test
    fun `accepts a valid icon, feature graphic and screenshots`() {
        assertEquals(512, StoreImageRules.validate(images.png("icon.png", 512, 512, alpha = true), "icon").width)
        StoreImageRules.validate(images.png("fg.png", 1024, 500), "featureGraphic")
        StoreImageRules.validate(images.jpeg("fg.jpg", 1024, 500), "featureGraphic")
        StoreImageRules.validate(images.png("shot.png", 1080, 1920), "phoneScreenshots")
        StoreImageRules.validate(images.jpeg("shot.jpg", 1920, 1080), "tenInchScreenshots")
    }

    @Test
    fun `reports the real format and size of the file`() {
        val info = StoreImageRules.validate(images.jpeg("shot.jpg", 1080, 1920), "phoneScreenshots")

        assertEquals("jpeg", info.format)
        assertEquals("image/jpeg", info.mimeType)
        assertEquals(1080 to 1920, info.width to info.height)
    }

    @Test
    fun `a PNG named jpg is described by its content, not its name`() {
        val file = images.file("really-png.jpg").also { images.png("tmp.png", 1080, 1920).copyTo(it) }

        assertEquals("image/png", StoreImageRules.validate(file, "phoneScreenshots").mimeType)
    }

    @Test
    fun `rejects an icon of the wrong size or format`() {
        assertContains(rejection(images.png("a.png", 511, 512), "icon"), "must be 512x512")
        assertContains(rejection(images.jpeg("b.jpg", 512, 512), "icon"), "must be one of [png]")
    }

    @Test
    fun `rejects an icon over 1 MB`() {
        assertContains(rejection(images.noisyPng("big.png", 512, 512), "icon"), "at most 1024 KB")
    }

    @Test
    fun `rejects a feature graphic of the wrong size or with transparency`() {
        assertContains(rejection(images.png("a.png", 1024, 501), "featureGraphic"), "must be 1024x500")
        assertContains(rejection(images.png("b.png", 1024, 500, alpha = true), "featureGraphic"), "alpha")
    }

    @Test
    fun `rejects screenshots outside Play's size rules`() {
        assertContains(rejection(images.png("a.png", 319, 640), "phoneScreenshots"), "320-3840")
        assertContains(rejection(images.png("b.png", 3841, 2000), "phoneScreenshots"), "320-3840")
        assertContains(rejection(images.png("d.png", 1080, 1920, alpha = true), "phoneScreenshots"), "alpha")
    }

    @Test
    fun `accepts tall modern phone screenshots that Play shows today`() {
        StoreImageRules.validate(images.jpeg("tall.jpg", 1080, 2400), "phoneScreenshots")
        StoreImageRules.validate(images.png("wide.png", 2400, 1080), "tenInchScreenshots")
    }

    @Test
    fun `accepts the boundary sizes`() {
        StoreImageRules.validate(images.png("min.png", 320, 320), "phoneScreenshots")
        StoreImageRules.validate(images.png("max.png", 3840, 1920), "phoneScreenshots")
    }

    @Test
    fun `rejects a file that is not an image`() {
        assertContains(rejection(images.text("fake.png"), "phoneScreenshots"), "not a PNG or JPEG")
        assertContains(rejection(images.text("empty.png", ""), "icon"), "not a PNG or JPEG")
    }

    @Test
    fun `rejects an unsupported image type`() {
        assertContains(rejection(images.png("a.png", 512, 512), "tvBanner"), "imageType must be one of")
    }

    @Test
    fun `has a rule for every slot the catalog reports`() {
        assertEquals(StoreImageSlots.maxPerType.keys, StoreImageRules.imageTypes)
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = java.util.zip.CRC32().apply { update(type.toByteArray()); update(data) }.value
            val buffer = java.nio.ByteBuffer.allocate(12 + data.size)
            buffer.putInt(data.size).put(type.toByteArray()).put(data).putInt(crc.toInt())
            return buffer.array()
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun `rejects huge declared dimensions from the header, before decoding any pixels`() {
        val crafted = images.file("huge.png").also { it.writeBytes(pngHeader(60000, 60000)) }

        assertContains(rejection(crafted, "phoneScreenshots"), "320-3840")
    }

    @Test
    fun `a corrupt image is a validation error, not a crash`() {
        val valid = images.png("ok.png", 1080, 1920)
        val truncated = images.file("cut.png").also { it.writeBytes(valid.readBytes().copyOf(60)) }

        assertContains(rejection(truncated, "phoneScreenshots"), "could not be read")
    }
}
