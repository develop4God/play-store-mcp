package io.devexpert.playstore

/** Image writes of the store listing. Implemented by [PlayImageClient]; tests substitute a fake. */
interface PlayImagePublisher {
    fun replaceImages(request: ReplaceStoreImagesRequest): WriteResult
}
