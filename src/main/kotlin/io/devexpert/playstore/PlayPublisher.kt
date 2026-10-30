package io.devexpert.playstore

/**
 * The Play Console operations this server exposes. Implemented by [PlayStoreClient];
 * tests substitute a fake so no credentials or network are needed.
 */
interface PlayPublisher {
    fun listReleases(packageName: String): List<PlayStoreRelease>

    fun deploy(request: DeployRequest): PlayStoreDeploymentResult

    fun promote(request: PromoteRequest): PlayStoreDeploymentResult
}
