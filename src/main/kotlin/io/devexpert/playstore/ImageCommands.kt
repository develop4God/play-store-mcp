package io.devexpert.playstore

import java.io.File

/** Applies the confirm gate: image writes reach the [PlayImagePublisher] only when the request says `confirm=true`. */
class ImageCommands(private val publisher: PlayImagePublisher) {
    fun replaceImages(request: ReplaceStoreImagesRequest): CommandResult {
        if (!request.confirm) {
            val files = request.images.joinToString("; ") {
                "${File(it.path).name} (${it.width}x${it.height}, ${it.bytes / BYTES_PER_KB} KB)"
            }
            return dryRunResult(
                "delete the current ${request.imageType} of ${request.language} for ${request.packageName} and upload, in this order: $files. " +
                    "It is saved as a draft and NOT sent for review; you send it from the Play Console",
                unchecked = "credentials, permissions and the current images"
            )
        }
        val result = publisher.replaceImages(request)
        return CommandResult(executed = true, success = result.success, message = (if (result.success) "SUCCESS\n" else "FAILED\n") + result.message)
    }

    private companion object {
        const val BYTES_PER_KB = 1024L
    }
}
