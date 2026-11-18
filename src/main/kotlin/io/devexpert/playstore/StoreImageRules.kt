package io.devexpert.playstore

import java.io.File
import javax.imageio.ImageIO

/** A local image that passed [StoreImageRules]; [format] is read from the file's content, not its name. */
data class ImageInfo(val path: String, val format: String, val width: Int, val height: Int, val bytes: Long) {
    val mimeType: String get() = if (format == "png") "image/png" else "image/jpeg"
}

/**
 * Google Play's asset rules, checked locally so a bad file fails in the dry run and not at the upload.
 * Covers the image types of [StoreImageSlots]; TV and Wear assets are not supported.
 */
object StoreImageRules {
    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val ICON_SIZE = 512
    private const val FEATURE_WIDTH = 1024
    private const val FEATURE_HEIGHT = 500
    private const val MIN_SCREENSHOT_SIDE = 320
    private const val MAX_SCREENSHOT_SIDE = 3840
    private const val MAX_ASPECT_RATIO = 2

    private data class Spec(val maxBytes: Long, val exactSize: Pair<Int, Int>?, val formats: Set<String>, val alphaAllowed: Boolean)

    private val screenshot = Spec(8 * MB, null, setOf("png", "jpeg"), alphaAllowed = false)

    private val specs = mapOf(
        "icon" to Spec(1 * MB, ICON_SIZE to ICON_SIZE, setOf("png"), alphaAllowed = true),
        "featureGraphic" to Spec(15 * MB, FEATURE_WIDTH to FEATURE_HEIGHT, setOf("png", "jpeg"), alphaAllowed = false),
        "phoneScreenshots" to screenshot,
        "sevenInchScreenshots" to screenshot,
        "tenInchScreenshots" to screenshot
    )

    val imageTypes: Set<String> = specs.keys

    // Validator: each throw rejects one distinct rule with its own message.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun validate(file: File, imageType: String): ImageInfo {
        val spec = specs[imageType]
            ?: throw InvalidRequestException("imageType must be one of $imageTypes, got '$imageType'")
        val name = file.name
        if (file.length() > spec.maxBytes) {
            throw InvalidRequestException("$name: $imageType must be at most ${spec.maxBytes / KB} KB; the file is ${file.length() / KB} KB")
        }
        // The header gives format and size without decoding pixels, so a crafted file cannot exhaust memory.
        val header = readHeader(file) ?: throw InvalidRequestException("$name is not a PNG or JPEG image")
        if (header.format !in spec.formats) {
            throw InvalidRequestException("$name: $imageType must be one of ${spec.formats}; the file is ${header.format}")
        }
        checkSize(name, imageType, spec, header.width, header.height)
        val image = try {
            ImageIO.read(file)
        } catch (e: Exception) {
            null
        } ?: throw InvalidRequestException("$name could not be read as an image")
        if (!spec.alphaAllowed && image.colorModel.hasAlpha()) {
            throw InvalidRequestException("$name: $imageType must not have an alpha (transparency) channel")
        }
        return ImageInfo(file.path, header.format, header.width, header.height, file.length())
    }

    private data class Header(val format: String, val width: Int, val height: Int)

    // The reader may reject a corrupt header in several ways; any of them means "not a usable image".
    @Suppress("TooGenericExceptionCaught")
    private fun readHeader(file: File): Header? = try {
        ImageIO.createImageInputStream(file)?.use { input ->
            val reader = ImageIO.getImageReaders(input).takeIf { it.hasNext() }?.next() ?: return null
            try {
                reader.input = input
                Header(reader.formatName.lowercase(), reader.getWidth(0), reader.getHeight(0))
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun checkSize(name: String, imageType: String, spec: Spec, width: Int, height: Int) {
        val exact = spec.exactSize
        if (exact != null) {
            if (width to height != exact) {
                throw InvalidRequestException("$name: $imageType must be ${exact.first}x${exact.second}; the image is ${width}x$height")
            }
            return
        }
        val sides = listOf(width, height)
        if (sides.any { it !in MIN_SCREENSHOT_SIDE..MAX_SCREENSHOT_SIDE }) {
            throw InvalidRequestException(
                "$name: $imageType sides must be $MIN_SCREENSHOT_SIDE-$MAX_SCREENSHOT_SIDE px; the image is ${width}x$height"
            )
        }
        if (sides.max() > MAX_ASPECT_RATIO * sides.min()) {
            throw InvalidRequestException(
                "$name: $imageType longest side must be at most $MAX_ASPECT_RATIO x the shortest; the image is ${width}x$height"
            )
        }
    }
}
