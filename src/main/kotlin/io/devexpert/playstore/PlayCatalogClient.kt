package io.devexpert.playstore

import com.google.api.services.androidpublisher.AndroidPublisher

/**
 * Read-only access to app details, listings and images of the Google Play Android Publisher API.
 */
class PlayCatalogClient internal constructor(publisherSupplier: () -> AndroidPublisher) : PlayCatalog {
    private val publisher: AndroidPublisher by lazy(publisherSupplier)

    override fun getAppDetails(packageName: String, language: String): AppDetailsView = publisher.readEdit(packageName) { editId ->
        val edits = publisher.edits()
        val details = edits.details().get(packageName, editId).execute()
        val listings = edits.listings().list(packageName, editId).execute()?.listings.orEmpty()
        val listing = listings.find { it.language == language }
            ?: throw PlayStoreException("No store listing for '$language'; available: ${listings.mapNotNull { it.language }.sorted()}")
        AppDetailsView(
            language = language,
            defaultLanguage = details?.defaultLanguage,
            contactEmail = details?.contactEmail,
            contactPhone = details?.contactPhone,
            contactWebsite = details?.contactWebsite,
            title = TextField.of(listing.title, MAX_TITLE_CHARACTERS),
            shortDescription = TextField.of(listing.shortDescription, MAX_SHORT_DESCRIPTION_CHARACTERS),
            fullDescription = TextField.of(listing.fullDescription, MAX_FULL_DESCRIPTION_CHARACTERS),
            video = listing.video,
            images = StoreImageSlots.maxPerType.mapValues { (type, max) -> imageGroup(packageName, editId, language, type, max) },
        )
    }

    private fun imageGroup(packageName: String, editId: String, language: String, type: String, max: Int): ImageGroup {
        // Play answers with an empty body, not an empty list, when there are no images.
        val images = publisher.edits().images().list(packageName, editId, language, type).execute()?.images.orEmpty()
        return ImageGroup(images.size, max, images.map { StoreImage(it.id.orEmpty(), it.url, it.sha256) })
    }

    private companion object {
        const val MAX_TITLE_CHARACTERS = 30
        const val MAX_SHORT_DESCRIPTION_CHARACTERS = 80
        const val MAX_FULL_DESCRIPTION_CHARACTERS = 4000
    }
}

/** Reads from a throwaway edit and always deletes it: nothing is committed. */
internal fun <T> AndroidPublisher.readEdit(packageName: String, block: (editId: String) -> T): T {
    val edits = edits()
    val editId = edits.insert(packageName, null).execute().id
    try {
        return block(editId)
    } finally {
        runCatching { edits.delete(packageName, editId).execute() }
    }
}
