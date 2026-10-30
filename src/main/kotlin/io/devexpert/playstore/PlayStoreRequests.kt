package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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

data class DeployRequest(
    val packageName: String,
    val track: String,
    val apkPath: String,
    val releaseNotes: Map<String, String>,
    val rolloutPercentage: Double,
    val confirm: Boolean
) {
    companion object {
        private val BUNDLE_EXTENSIONS = setOf("aab", "apk")

        fun parse(args: Map<String, JsonElement>): DeployRequest {
            val track = args.requireString("track")
            if (track !in TrackPolicy.deployableTracks) {
                throw InvalidRequestException("track must be one of ${TrackPolicy.deployableTracks}, got '$track'")
            }
            val apkPath = args.requireString("apkPath")
            val file = File(apkPath)
            if (!file.isFile) throw InvalidRequestException("file not found: $apkPath")
            if (file.extension.lowercase() !in BUNDLE_EXTENSIONS) {
                throw InvalidRequestException("apkPath must be an .aab or .apk file")
            }
            return DeployRequest(
                packageName = args.requirePackageName(),
                track = track,
                apkPath = apkPath,
                releaseNotes = args.optionalReleaseNotes(),
                rolloutPercentage = args.optionalRollout(),
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
    companion object {
        fun parse(args: Map<String, JsonElement>): PromoteRequest {
            val fromTrack = args.requireString("fromTrack")
            val toTrack = args.requireString("toTrack")
            if (fromTrack to toTrack !in TrackPolicy.allowedPromotions) {
                throw InvalidRequestException(
                    "promotion $fromTrack -> $toTrack is not allowed; allowed: ${TrackPolicy.allowedPromotions}"
                )
            }
            val versionCode = args.requireString("versionCode").toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw InvalidRequestException("versionCode must be a positive integer")
            return PromoteRequest(
                packageName = args.requirePackageName(),
                fromTrack = fromTrack,
                toTrack = toTrack,
                versionCode = versionCode,
                confirm = args.optionalConfirm()
            )
        }
    }
}

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

private fun Map<String, JsonElement>.requireString(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: throw InvalidRequestException("$key is required")
    return primitive.content.takeIf { it.isNotBlank() } ?: throw InvalidRequestException("$key must not be blank")
}

private fun Map<String, JsonElement>.requirePackageName(): String {
    val name = requireString("packageName")
    if (!PACKAGE_NAME.matches(name)) throw InvalidRequestException("packageName is not a valid application id: '$name'")
    return name
}

private fun Map<String, JsonElement>.optionalConfirm(): Boolean {
    val primitive = this["confirm"] as? JsonPrimitive ?: return false
    if (primitive.isString) throw InvalidRequestException("confirm must be a boolean")
    return primitive.booleanOrNull ?: throw InvalidRequestException("confirm must be a boolean")
}

private fun Map<String, JsonElement>.optionalRollout(): Double {
    val primitive = this["rolloutPercentage"] as? JsonPrimitive ?: return 1.0
    val value = primitive.doubleOrNull ?: throw InvalidRequestException("rolloutPercentage must be a number")
    if (value <= 0.0 || value > 1.0) throw InvalidRequestException("rolloutPercentage must be in (0.0, 1.0]")
    return value
}

private fun Map<String, JsonElement>.optionalReleaseNotes(): Map<String, String> {
    val element = this["releaseNotes"] ?: return emptyMap()
    val notes = element as? JsonObject
        ?: throw InvalidRequestException("releaseNotes must be an object of language tag to text")
    return notes.mapValues { (language, text) ->
        val primitive = text as? JsonPrimitive
        primitive?.content?.takeIf { it.isNotBlank() }
            ?: throw InvalidRequestException("release notes for '$language' must not be blank")
    }
}
