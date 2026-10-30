package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * Thrown when tool arguments are missing or unsafe. Nothing reaches the Play API in that case.
 */
class InvalidRequestException(message: String) : Exception(message)

/**
 * Tracks this server may write to, and the only promotion it may perform.
 * Production and open testing (beta) are deliberately unreachable.
 */
object TrackPolicy {
    val deployableTracks = setOf("internal", "alpha")
    val allowedPromotions = setOf("internal" to "alpha")
}

/**
 * The policy is enforced in `init`, so a request cannot be built (or `copy`-ed) around it.
 */
data class DeployRequest(
    val packageName: String,
    val track: String,
    val apkPath: String,
    val releaseNotes: Map<String, String>,
    val confirm: Boolean
) {
    init {
        validatePackageName(packageName)
        if (track !in TrackPolicy.deployableTracks) {
            throw InvalidRequestException("track must be one of ${TrackPolicy.deployableTracks}, got '$track'")
        }
    }

    companion object {
        private val BUNDLE_EXTENSIONS = setOf("aab", "apk")

        fun parse(args: Map<String, JsonElement>): DeployRequest {
            if ("rolloutPercentage" in args) {
                throw InvalidRequestException("rolloutPercentage is not supported: testing tracks have no staged rollout")
            }
            val file = File(args.requireString("apkPath"))
            if (!file.isAbsolute) throw InvalidRequestException("apkPath must be an absolute path")
            val resolved = file.canonicalFile
            if (!resolved.isFile) throw InvalidRequestException("file not found: ${file.path}")
            if (resolved.extension.lowercase() !in BUNDLE_EXTENSIONS) {
                throw InvalidRequestException("apkPath must be an .aab or .apk file")
            }
            return DeployRequest(
                packageName = args.requireString("packageName"),
                track = args.requireString("track"),
                apkPath = resolved.path,
                releaseNotes = args.optionalReleaseNotes(),
                confirm = args.optionalConfirm()
            )
        }
    }
}

data class PromoteRequest(
    val packageName: String,
    val fromTrack: String,
    val toTrack: String,
    val versionCode: Long,
    val confirm: Boolean
) {
    init {
        validatePackageName(packageName)
        if (fromTrack to toTrack !in TrackPolicy.allowedPromotions) {
            throw InvalidRequestException(
                "promotion $fromTrack -> $toTrack is not allowed; allowed: ${TrackPolicy.allowedPromotions}"
            )
        }
        if (versionCode <= 0) throw InvalidRequestException("versionCode must be a positive integer")
    }

    companion object {
        fun parse(args: Map<String, JsonElement>): PromoteRequest {
            val versionCode = args.requireString("versionCode").toLongOrNull()
                ?: throw InvalidRequestException("versionCode must be a positive integer")
            return PromoteRequest(
                packageName = args.requireString("packageName"),
                fromTrack = args.requireString("fromTrack"),
                toTrack = args.requireString("toTrack"),
                versionCode = versionCode,
                confirm = args.optionalConfirm()
            )
        }
    }
}

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

fun validatePackageName(name: String): String {
    if (!PACKAGE_NAME.matches(name)) throw InvalidRequestException("packageName is not a valid application id: '$name'")
    return name
}

private fun Map<String, JsonElement>.requireString(key: String): String {
    val primitive = this[key] as? JsonPrimitive
    if (primitive == null || primitive is JsonNull) throw InvalidRequestException("$key is required")
    return primitive.content.takeIf { it.isNotBlank() } ?: throw InvalidRequestException("$key must not be blank")
}

private fun Map<String, JsonElement>.optionalConfirm(): Boolean {
    val primitive = this["confirm"] as? JsonPrimitive ?: return false
    if (primitive.isString) throw InvalidRequestException("confirm must be a boolean")
    return primitive.booleanOrNull ?: throw InvalidRequestException("confirm must be a boolean")
}

private fun Map<String, JsonElement>.optionalReleaseNotes(): Map<String, String> {
    val element = this["releaseNotes"] ?: return emptyMap()
    val notes = element as? JsonObject
        ?: throw InvalidRequestException("releaseNotes must be an object of language tag to text")
    return notes.mapValues { (language, text) ->
        val primitive = text as? JsonPrimitive
        primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw InvalidRequestException("release notes for '$language' must be non-blank text")
    }
}
