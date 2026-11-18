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
        logger.info("Replacing {} in {} of {}", request.imageType, request.language, request.packageName)
        return publisher.commitHeldEdit(request.packageName) { editId ->
            val images = publisher.edits().images()
            images.deleteall(request.packageName, editId, request.language, request.imageType).execute()
            val ids = request.images.map { image ->
                images.upload(request.packageName, editId, request.language, request.imageType, FileContent(image.mimeType, File(image.path)))
                    .execute().image?.id.orEmpty()
            }
            "Replaced the ${request.imageType} of ${request.language} with ${ids.size} image(s), ids $ids"
        }
    }
}
