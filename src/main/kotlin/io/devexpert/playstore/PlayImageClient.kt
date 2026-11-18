package io.devexpert.playstore

import com.google.api.client.http.FileContent
import com.google.api.services.androidpublisher.AndroidPublisher
import org.slf4j.LoggerFactory
import java.io.File

/** Replaces store listing images through the Google Play Android Publisher API. */
class PlayImageClient internal constructor(publisherSupplier: () -> AndroidPublisher) : PlayImagePublisher {
    private val logger = LoggerFactory.getLogger(PlayImageClient::class.java)
    private val publisher: AndroidPublisher by lazy(publisherSupplier)

    override fun replaceImages(request: ReplaceStoreImagesRequest): WriteResult {
        logger.info("Replacing images in {} slot(s) of {}", request.replacements.size, request.packageName)
        return publisher.commitStoreEdit(request.packageName, request.sendForReview) { editId ->
            val summaries = request.replacements.map { replace(request.packageName, editId, it) }
            "Replaced " + summaries.joinToString("; ")
        }
    }

    private fun replace(packageName: String, editId: String, replacement: ImageReplacement): String {
        val images = publisher.edits().images()
        images.deleteall(packageName, editId, replacement.language, replacement.imageType).execute()
        val ids = replacement.images.map { image ->
            images.upload(packageName, editId, replacement.language, replacement.imageType, FileContent(image.mimeType, File(image.path)))
                .execute().image?.id.orEmpty()
        }
        return "${replacement.language} ${replacement.imageType} (${ids.size} image(s), ids $ids)"
    }
}
