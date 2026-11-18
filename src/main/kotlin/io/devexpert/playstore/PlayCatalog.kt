package io.devexpert.playstore

import kotlinx.serialization.Serializable

/**
 * Read-only store information of an app. Kept apart from [PlayPublisher] because nothing here changes Play.
 */
interface PlayCatalog {
    /** Everything the Play Console shows for one language of the default store listing. */
    fun getAppDetails(packageName: String, language: String): AppDetailsView
}

/** The image types this server reads, with the most images Play accepts for each. */
object StoreImageSlots {
    private const val MAX_SCREENSHOTS = 8

    val maxPerType: Map<String, Int> = linkedMapOf(
        "icon" to 1,
        "featureGraphic" to 1,
        "phoneScreenshots" to MAX_SCREENSHOTS,
        "sevenInchScreenshots" to MAX_SCREENSHOTS,
        "tenInchScreenshots" to MAX_SCREENSHOTS,
    )
}

/** A listing text with its length counted as the Play Console counts it (UTF-16 units, so an emoji is 2). */
@Serializable
data class TextField(val text: String?, val length: Int, val max: Int) {
    companion object {
        fun of(text: String?, max: Int) = TextField(text, text?.length ?: 0, max)
    }
}

@Serializable
data class StoreImage(val id: String, val url: String?, val sha256: String?)

@Serializable
data class ImageGroup(val count: Int, val max: Int, val images: List<StoreImage>)

@Serializable
data class AppDetailsView(
    val language: String,
    val defaultLanguage: String?,
    val contactEmail: String?,
    val contactPhone: String?,
    val contactWebsite: String?,
    val title: TextField,
    val shortDescription: TextField,
    val fullDescription: TextField,
    val video: String?,
    val images: Map<String, ImageGroup>,
)
