package io.devexpert.playstore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * The files are validated in `init`, so a request cannot be built around [StoreImageRules].
 */
data class ReplaceStoreImagesRequest(
    val packageName: String,
    val language: String,
    val imageType: String,
    val imagePaths: List<String>,
    val confirm: Boolean
) {
    init {
        validatePackageName(packageName)
        validateLanguageTag(language)
        val max = StoreImageSlots.maxPerType[imageType]
            ?: throw InvalidRequestException("imageType must be one of ${StoreImageSlots.maxPerType.keys}, got '$imageType'")
        if (imagePaths.isEmpty()) throw InvalidRequestException("imagePaths must list at least one image")
        if (imagePaths.size > max) throw InvalidRequestException("$imageType takes at most $max image(s); got ${imagePaths.size}")
        if (imagePaths.map { File(it).canonicalPath }.toSet().size != imagePaths.size) throw InvalidRequestException("imagePaths must not repeat a file")
    }

    val images: List<ImageInfo> = imagePaths.map { StoreImageRules.validate(File(it), imageType) }

    companion object {
        fun parse(args: Map<String, JsonElement>) = ReplaceStoreImagesRequest(
            packageName = args.requireString("packageName"),
            language = args.requireString("language"),
            imageType = args.requireString("imageType"),
            imagePaths = args.requireImagePaths(),
            confirm = args.optionalConfirm()
        )

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
