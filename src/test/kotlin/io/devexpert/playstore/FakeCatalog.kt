package io.devexpert.playstore

/** Answers from fixed data and never touches credentials or the network. [failure] makes each call throw. */
internal class FakeCatalog(private val failure: Exception? = null) : PlayCatalog {
    val calls = mutableListOf<String>()

    private fun <T> answer(call: String, value: T): T {
        calls += call
        failure?.let { throw it }
        return value
    }

    override fun getAppDetails(packageName: String) = answer("details:$packageName", AppDetailsInfo("en-US", "dev@example.com", null, null))

    override fun listStoreListings(packageName: String) =
        answer("listings:$packageName", listOf(StoreListingInfo("en-US", "My App", "short", "full")))

    override fun listInAppProducts(packageName: String) = answer(
        "products:$packageName",
        listOf(InAppProductInfo("coffee", "active", "managedUser", "en-US", PriceInfo("USD", "990000"), emptyList())),
    )

    override fun listSubscriptions(packageName: String) =
        answer("subscriptions:$packageName", listOf(SubscriptionInfo("pro", listOf(BasePlanInfo("monthly", "ACTIVE")), emptyList())))
}
