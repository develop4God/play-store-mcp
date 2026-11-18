package io.devexpert.playstore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

fun validateLanguageTag(language: String): String {
    if (!LANGUAGE_TAG.matches(language)) {
        throw InvalidRequestException("language must be a Play language tag such as en-US, got '$language'")
    }
    return language
}

/**
 * Replaces every image of one [imageType] in one [language] with [imagePaths], in that order.
 * The files are validated in `init`, so it cannot be built around [StoreImageRules].
 */
data class ImageReplacement(val language: String, val imageType: String, val imagePaths: List<String>) {
    init {
        validateLanguageTag(language)
        val max = StoreImageSlots.maxPerType[imageType]
            ?: throw InvalidRequestException("imageType must be one of ${StoreImageSlots.maxPerType.keys}, got '$imageType'")
        if (imagePaths.isEmpty()) throw InvalidRequestException("imagePaths must list at least one image ($language $imageType)")
        if (imagePaths.size > max) throw InvalidRequestException("$imageType takes at most $max image(s); got ${imagePaths.size} for $language")
        if (imagePaths.map { File(it).canonicalPath }.toSet().size != imagePaths.size) {
            throw InvalidRequestException("imagePaths must not repeat a file ($language $imageType)")
        }
    }

    val images: List<ImageInfo> = imagePaths.map { StoreImageRules.validate(File(it), imageType) }
}

/**
 * Many [replacements] applied in ONE Play edit, so they are committed (and reviewed by Google) together.
 * With [sendForReview] false the commit is held back from review; with true it is sent to Google review.
 */
data class ReplaceStoreImagesRequest(
    val packageName: String,
    val replacements: List<ImageReplacement>,
    val confirm: Boolean,
    val sendForReview: Boolean = false
) {
    init {
        validatePackageName(packageName)
        if (replacements.isEmpty()) throw InvalidRequestException("replacements must list at least one entry")
        val targets = replacements.map { it.language to it.imageType }
        if (targets.toSet().size != targets.size) throw InvalidRequestException("each language and imageType may appear only once")
    }

    companion object {
        fun parse(args: Map<String, JsonElement>) = ReplaceStoreImagesRequest(
            packageName = args.requireString("packageName"),
            replacements = args.requireReplacements(),
            confirm = args.optionalConfirm(),
            sendForReview = args.optionalBoolean("sendForReview")
        )

        private fun Map<String, JsonElement>.requireReplacements(): List<ImageReplacement> {
            val array = this["replacements"] as? JsonArray
                ?: throw InvalidRequestException("replacements must be an array of {language, imageType, imagePaths}")
            return array.map { element ->
                val entry = element as? JsonObject
                    ?: throw InvalidRequestException("every replacements entry must be an object of language, imageType and imagePaths")
                ImageReplacement(entry.requireString("language"), entry.requireString("imageType"), entry.requireImagePaths())
            }
        }

        // Validator: each throw rejects one distinct invalid input with its own message.
        @Suppress("ThrowsCount")
        private fun Map<String, JsonElement>.requireImagePaths(): List<String> {
            val array = this["imagePaths"] as? JsonArray
                ?: throw InvalidRequestException("imagePaths must be an array of absolute file paths")
            return array.map { element ->
                val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: throw InvalidRequestException("every imagePaths entry must be a non-blank path")
                val file = File(text)
                if (!file.isAbsolute) throw InvalidRequestException("imagePaths must be absolute paths: $text")
                val resolved = file.canonicalFile
                if (!resolved.isFile) throw InvalidRequestException("file not found: $text")
                if (resolved.extension.lowercase() !in IMAGE_EXTENSIONS) {
                    throw InvalidRequestException("${resolved.name} must be a .png, .jpg or .jpeg file")
                }
                resolved.path
            }
        }
    }
}
