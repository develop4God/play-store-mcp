package io.devexpert.playstore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Thrown when tool arguments are missing or unsafe. Nothing reaches the Play API in that case.
 */
class InvalidRequestException(message: String) : Exception(message)

/**
 * Tracks this server may write to, and the only promotions it may perform.
 *
 * Production is reachable only as a held draft: the release is saved as a draft and the edit is committed
 * with changesNotSentForReview, so nothing reaches Google review or users until a human sends it for
 * review in the Play Console. Promotion never targets production.
 */
object TrackPolicy {
    private const val PRODUCTION = "production"

    val deployableTracks = setOf("internal", "alpha", "beta", PRODUCTION)
    val allowedPromotions = setOf("internal" to "alpha", "internal" to "beta", "alpha" to "beta")
    val promotionSources = allowedPromotions.map { it.first }.toSet()
    val promotionTargets = allowedPromotions.map { it.second }.toSet()

    val notesEditableTracks = deployableTracks

    fun holdsForReview(track: String) = track == PRODUCTION

    fun releaseStatusFor(track: String) = if (holdsForReview(track)) "draft" else "completed"
}

/**
 * The policy is enforced in `init`, so a request cannot be built (or `copy`-ed) around it.
 */
data class DeployRequest(
    val packageName: String,
    val track: String,
    val apkPath: String,
    val releaseNotes: Map<String, String>,
    val confirm: Boolean,
    val inAppUpdatePriority: Int? = null,
) {
    init {
        validatePackageName(packageName)
        if (track !in TrackPolicy.deployableTracks) {
            throw InvalidRequestException("track must be one of ${TrackPolicy.deployableTracks}, got '$track'")
        }
        if (inAppUpdatePriority != null && inAppUpdatePriority !in IN_APP_UPDATE_PRIORITIES) {
            throw InvalidRequestException(IN_APP_UPDATE_PRIORITY_ERROR)
        }
    }

    companion object {
        private val BUNDLE_EXTENSIONS = setOf("aab", "apk")

        // Validator: each throw rejects one distinct invalid input with its own message.
        @Suppress("ThrowsCount")
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
                confirm = args.optionalConfirm(),
                inAppUpdatePriority = args.optionalInAppUpdatePriority(),
            )
        }
    }
}

data class PromoteRequest(
    val packageName: String,
    val fromTrack: String,
    val toTrack: String,
    val versionCode: Long,
    val confirm: Boolean,
) {
    init {
        validatePackageName(packageName)
        if (fromTrack to toTrack !in TrackPolicy.allowedPromotions) {
            throw InvalidRequestException(
                "promotion $fromTrack -> $toTrack is not allowed; allowed: ${TrackPolicy.allowedPromotions}",
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
                confirm = args.optionalConfirm(),
            )
        }
    }
}

data class UpdateReleaseNotesRequest(
    val packageName: String,
    val track: String,
    val versionCode: Long,
    val releaseNotes: Map<String, String>,
    val confirm: Boolean,
) {
    init {
        validatePackageName(packageName)
        if (track !in TrackPolicy.notesEditableTracks) {
            throw InvalidRequestException("track must be one of ${TrackPolicy.notesEditableTracks}, got '$track'")
        }
        if (versionCode <= 0) throw InvalidRequestException("versionCode must be a positive integer")
        if (releaseNotes.isEmpty()) throw InvalidRequestException("releaseNotes must not be empty")
    }

    companion object {
        fun parse(args: Map<String, JsonElement>) = UpdateReleaseNotesRequest(
            packageName = args.requireString("packageName"),
            track = args.requireString("track"),
            versionCode = args.requireString("versionCode").toLongOrNull()
                ?: throw InvalidRequestException("versionCode must be a positive integer"),
            releaseNotes = args.requireReleaseNotes(),
            confirm = args.optionalConfirm(),
        )
    }
}

/** Google Play rejects a release note longer than this, per language. */
const val MAX_RELEASE_NOTE_CHARACTERS = 500

private const val MAX_IN_APP_UPDATE_PRIORITY = 5

/** Play treats priorities from this value up as urgent. */
private const val MIN_URGENT_IN_APP_UPDATE_PRIORITY = 4

/** Play's in-app update priority range. */
private val IN_APP_UPDATE_PRIORITIES = 0..MAX_IN_APP_UPDATE_PRIORITY

/** Human-readable priority; urgent ones are flagged because apps may block users until they update. */
internal fun describeInAppUpdatePriority(priority: Int): String = if (priority >= MIN_URGENT_IN_APP_UPDATE_PRIORITY) {
    "$priority (URGENT: apps may force an immediate update)"
} else {
    "$priority"
}

private const val IN_APP_UPDATE_PRIORITY_ERROR = "inAppUpdatePriority must be an integer from 0 to $MAX_IN_APP_UPDATE_PRIORITY"

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

private fun Map<String, JsonElement>.optionalInAppUpdatePriority(): Int? {
    val element = this["inAppUpdatePriority"] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) throw InvalidRequestException(IN_APP_UPDATE_PRIORITY_ERROR)
    // intOrNull accepts some integral numbers not written as integers (covered by DeployRequestTest);
    // requiring the canonical form keeps the input strictly an integer. The range is checked in DeployRequest.init.
    return primitive.intOrNull?.takeIf { primitive.content == it.toString() }
        ?: throw InvalidRequestException(IN_APP_UPDATE_PRIORITY_ERROR)
}

private fun Map<String, JsonElement>.requireReleaseNotes(): Map<String, String> {
    if ("releaseNotes" !in this) throw InvalidRequestException("releaseNotes is required")
    return optionalReleaseNotes()
}

// Validator: each throw rejects one distinct invalid input with its own message.
@Suppress("ThrowsCount")
private fun Map<String, JsonElement>.optionalReleaseNotes(): Map<String, String> {
    val element = this["releaseNotes"] ?: return emptyMap()
    val notes = element as? JsonObject
        ?: throw InvalidRequestException("releaseNotes must be an object of language tag to text")
    return notes.mapValues { (language, text) ->
        val primitive = text as? JsonPrimitive
        val content = primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw InvalidRequestException("release notes for '$language' must be non-blank text")
        val length = content.codePointCount(0, content.length)
        if (length > MAX_RELEASE_NOTE_CHARACTERS) {
            throw InvalidRequestException(
                "release notes for '$language' are $length characters; " +
                    "Google Play allows at most $MAX_RELEASE_NOTE_CHARACTERS",
            )
        }
        content
    }
}
