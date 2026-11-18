package io.devexpert.playstore

import kotlinx.serialization.Serializable

/**
 * Read-only store information of an app: details, listings and monetization products.
 * Kept apart from [PlayPublisher] because these operations never change anything in Play.
 */
interface PlayCatalog {
    fun getAppDetails(packageName: String): AppDetailsInfo

    fun listStoreListings(packageName: String): List<StoreListingInfo>

    fun listInAppProducts(packageName: String): List<InAppProductInfo>

    fun listSubscriptions(packageName: String): List<SubscriptionInfo>
}

@Serializable
data class AppDetailsInfo(val defaultLanguage: String?, val contactEmail: String?, val contactPhone: String?, val contactWebsite: String?)

@Serializable
data class StoreListingInfo(val language: String, val title: String?, val shortDescription: String?, val fullDescription: String?)

@Serializable
data class PriceInfo(val currency: String?, val priceMicros: String?)

@Serializable
data class ListingText(val language: String, val title: String?, val description: String?)

@Serializable
data class InAppProductInfo(
    val sku: String,
    val status: String?,
    val purchaseType: String?,
    val defaultLanguage: String?,
    val defaultPrice: PriceInfo?,
    val listings: List<ListingText>,
)

@Serializable
data class BasePlanInfo(val basePlanId: String, val state: String?)

@Serializable
data class SubscriptionInfo(val productId: String, val basePlans: List<BasePlanInfo>, val listings: List<ListingText>)
