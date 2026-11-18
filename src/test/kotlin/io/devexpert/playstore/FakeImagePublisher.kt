package io.devexpert.playstore

/** Records every call and never touches credentials or the network. [failure] makes the call throw. */
internal class FakeImagePublisher(private val failure: Exception? = null) : PlayImagePublisher {
    val calls = mutableListOf<ReplaceStoreImagesRequest>()

    override fun replaceImages(request: ReplaceStoreImagesRequest): WriteResult {
        calls += request
        failure?.let { throw it }
        return WriteResult(true, "edit-1", "Replaced ${request.images.size} image(s). Saved as a draft")
    }
}
