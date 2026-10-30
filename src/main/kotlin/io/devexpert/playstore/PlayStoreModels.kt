package io.devexpert.playstore

import kotlinx.serialization.Serializable

/**
 * A release on one track, as reported by the Play API.
 */
@Serializable
data class PlayStoreRelease(
    val track: String,
    val status: String, // completed, inProgress, draft, halted
    val versionCodes: List<Long>,
    val userFraction: Double?,
    val name: String?
)

/**
 * Result of a deployment or promotion. [versionCode] comes from the Play API, never from the caller.
 */
data class PlayStoreDeploymentResult(
    val success: Boolean,
    val deploymentId: String?,
    val packageName: String,
    val track: String,
    val versionCode: Long?,
    val message: String
)
