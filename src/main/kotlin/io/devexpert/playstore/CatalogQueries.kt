package io.devexpert.playstore

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

/**
 * Read-only queries over the store catalog. Validates the arguments and renders results as JSON;
 * nothing here can modify anything in Play, so there is no confirm gate.
 */
class CatalogQueries(private val catalog: PlayCatalog) {
    private val logger = LoggerFactory.getLogger(CatalogQueries::class.java)
    private val json = Json { prettyPrint = true }

    // Error boundary: any Play API failure becomes a failed CommandResult instead of escaping to the transport.
    @Suppress("TooGenericExceptionCaught")
    fun appDetails(packageName: String, language: String): CommandResult {
        val validatedPackage = validatePackageName(packageName)
        if (!LANGUAGE_TAG.matches(language)) {
            throw InvalidRequestException("language must be a Play language tag such as en-US, got '$language'")
        }
        return try {
            val view = catalog.getAppDetails(validatedPackage, language)
            CommandResult(executed = true, success = true, message = json.encodeToString(AppDetailsView.serializer(), view))
        } catch (e: Exception) {
            logger.error("Catalog query failed: {}", e.message)
            CommandResult(executed = true, success = false, message = "Operation failed: ${e.message}")
        }
    }
}
