package io.devexpert.playstore

import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.InAppProduct
import com.google.api.services.androidpublisher.model.Subscription

/**
 * Read-only access to app details, listings and monetization products of the Google Play Android Publisher API.
 */
class PlayCatalogClient internal constructor(publisherSupplier: () -> AndroidPublisher) : PlayCatalog {
    private val publisher: AndroidPublisher by lazy(publisherSupplier)

    override fun getAppDetails(packageName: String): AppDetailsInfo = publisher.readEdit(packageName) { editId ->
        val details = publisher.edits().details().get(packageName, editId).execute()
        AppDetailsInfo(details.defaultLanguage, details.contactEmail, details.contactPhone, details.contactWebsite)
    }

    override fun listStoreListings(packageName: String): List<StoreListingInfo> = publisher.readEdit(packageName) { editId ->
        publisher.edits().listings().list(packageName, editId).execute().listings.orEmpty().map {
            StoreListingInfo(it.language.orEmpty(), it.title, it.shortDescription, it.fullDescription)
        }
    }

    override fun listInAppProducts(packageName: String): List<InAppProductInfo> = allPages { token ->
        val response = publisher.inappproducts().list(packageName).setToken(token).execute()
        response.inappproduct.orEmpty().map { it.toInfo() } to response.tokenPagination?.nextPageToken
    }

    override fun listSubscriptions(packageName: String): List<SubscriptionInfo> = allPages { token ->
        val response = publisher.monetization().subscriptions().list(packageName).setPageToken(token).execute()
        response.subscriptions.orEmpty().map { it.toInfo() } to response.nextPageToken
    }

    private fun InAppProduct.toInfo() = InAppProductInfo(
        sku = sku.orEmpty(),
        status = status,
        purchaseType = purchaseType,
        defaultLanguage = defaultLanguage,
        defaultPrice = defaultPrice?.let { PriceInfo(it.currency, it.priceMicros) },
        listings = listings.orEmpty().map { (language, listing) -> ListingText(language, listing.title, listing.description) },
    )

    private fun Subscription.toInfo() = SubscriptionInfo(
        productId = productId.orEmpty(),
        basePlans = basePlans.orEmpty().map { BasePlanInfo(it.basePlanId.orEmpty(), it.state) },
        listings = listings.orEmpty().map { ListingText(it.languageCode.orEmpty(), it.title, it.description) },
    )
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

/** Collects every page; [fetch] gets the page token (null for the first page) and returns the items and the next token. */
private fun <T> allPages(fetch: (token: String?) -> Pair<List<T>, String?>): List<T> {
    val items = mutableListOf<T>()
    var token: String? = null
    do {
        val (page, next) = fetch(token)
        items += page
        token = next
    } while (!token.isNullOrEmpty())
    return items
}
