package io.devexpert.playstore

import java.io.File

/** Applies the confirm gate: image writes reach the [PlayImagePublisher] only when the request says `confirm=true`. */
class ImageCommands(private val publisher: PlayImagePublisher) {
    fun replaceImages(request: ReplaceStoreImagesRequest): CommandResult {
        if (!request.confirm) {
            val plan = request.replacements.joinToString("; ") { replacement ->
                val files = replacement.images.joinToString(", ") { "${File(it.path).name} (${it.width}x${it.height}, ${it.bytes / BYTES_PER_KB} KB)" }
                "${replacement.language} ${replacement.imageType}: $files"
            }
            return dryRunResult(
                "delete the current images of these slots of ${request.packageName} and upload, in this order: $plan. ${reviewNote(request)}",
                unchecked = "credentials, permissions and the current images"
            )
        }
        val result = publisher.replaceImages(request)
        return CommandResult(executed = true, success = result.success, message = (if (result.success) "SUCCESS\n" else "FAILED\n") + result.message)
    }

    private fun reviewNote(request: ReplaceStoreImagesRequest) = if (request.sendForReview) {
        "It will be SENT TO GOOGLE REVIEW in one submission; with managed publishing on, it does not go live until you publish it"
    } else {
        "It is saved as a draft and NOT sent for review; if this app cannot hold changes back, it fails and nothing changes " +
            "(run again with sendForReview=true to send it to Google review)"
    }

    private companion object {
        const val BYTES_PER_KB = 1024L
    }
}
