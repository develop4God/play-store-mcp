package io.devexpert.playstore

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Outcome of a write command. [executed] is false for a dry run, in which case nothing was sent to Play.
 */
data class CommandResult(val executed: Boolean, val success: Boolean, val message: String)

/**
 * Applies the confirm gate: writes only reach the [PlayPublisher] when the request says `confirm=true`.
 */
class ReleaseCommands(private val publisher: PlayPublisher) {
    private val json = Json { prettyPrint = true }

    fun listReleases(packageName: String): String {
        val releases = publisher.listReleases(validatePackageName(packageName))
        return json.encodeToString(ListSerializer(PlayStoreRelease.serializer()), releases)
    }

    fun deploy(request: DeployRequest): CommandResult {
        if (!request.confirm) {
            val held = heldNote(request.track)
            val sizeMb = File(request.apkPath).length() / BYTES_PER_MB
            return dryRun(
                "upload ${request.apkPath} (${sizeMb} MB) to the ${request.track} track of " +
                    "${request.packageName}, release notes in ${request.releaseNotes.keys.ifEmpty { setOf("no language") }}$held"
            )
        }
        return toCommandResult(publisher.deploy(request))
    }

    fun promote(request: PromoteRequest): CommandResult {
        if (!request.confirm) {
            return dryRun(
                "promote version ${request.versionCode} of ${request.packageName} " +
                    "from ${request.fromTrack} to ${request.toTrack}"
            )
        }
        return toCommandResult(publisher.promote(request))
    }

    fun updateReleaseNotes(request: UpdateReleaseNotesRequest): CommandResult {
        if (!request.confirm) {
            return dryRun(
                "replace the release notes of version ${request.versionCode} on the ${request.track} track of " +
                    "${request.packageName} with notes in ${request.releaseNotes.keys}${heldNote(request.track)}"
            )
        }
        return toCommandResult(publisher.updateReleaseNotes(request))
    }

    private fun heldNote(track: String) =
        if (TrackPolicy.holdsForReview(track)) {
            " (production: saved as a draft only, NOT sent for review; a human sends it in the Play Console)"
        } else {
            ""
        }

    private fun dryRun(plan: String) = CommandResult(
        executed = false,
        success = true,
        message = "DRY RUN - nothing was sent to Google Play.\nWould $plan.\n" +
            "Only the input was validated: credentials, permissions, version code and track state are not checked " +
            "until you run again with confirm=true."
    )

    private fun toCommandResult(result: PlayStoreDeploymentResult) = CommandResult(
        executed = true,
        success = result.success,
        message = buildString {
            appendLine(if (result.success) "SUCCESS" else "FAILED")
            appendLine("Package: ${result.packageName}")
            appendLine("Track: ${result.track}")
            result.versionCode?.let { appendLine("Version code: $it") }
            result.deploymentId?.let { appendLine("Edit id: $it") }
            append(result.message)
        }
    )

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
