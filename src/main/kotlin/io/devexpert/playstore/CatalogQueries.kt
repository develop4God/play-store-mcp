package io.devexpert.playstore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Read-only queries over the store catalog. Validates the package name and renders results as JSON;
 * nothing here can modify anything in Play, so there is no confirm gate.
 */
class CatalogQueries(private val catalog: PlayCatalog) {
    private val logger = LoggerFactory.getLogger(CatalogQueries::class.java)
    private val json = Json { prettyPrint = true }

    fun appDetails(packageName: String) = query(packageName, AppDetailsInfo.serializer(), catalog::getAppDetails)

    fun storeListings(packageName: String) = query(packageName, ListSerializer(StoreListingInfo.serializer()), catalog::listStoreListings)

    fun inAppProducts(packageName: String) = query(packageName, ListSerializer(InAppProductInfo.serializer()), catalog::listInAppProducts)

    fun subscriptions(packageName: String) = query(packageName, ListSerializer(SubscriptionInfo.serializer()), catalog::listSubscriptions)

    // Error boundary: any Play API failure becomes a failed CommandResult instead of escaping to the transport.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> query(packageName: String, serializer: KSerializer<T>, fetch: (String) -> T): CommandResult {
        val validated = validatePackageName(packageName)
        return try {
            CommandResult(executed = true, success = true, message = json.encodeToString(serializer, fetch(validated)))
        } catch (e: Exception) {
            logger.error("Catalog query failed: {}", e.message)
            CommandResult(executed = true, success = false, message = "Operation failed: ${e.message}")
        }
    }
}
