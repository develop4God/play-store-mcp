package io.devexpert.playstore

/** Records every call and never touches credentials or the network. [failure] makes each call throw. */
internal class FakePublisher(
    private val deployResult: PlayStoreDeploymentResult? = null,
    private val releases: List<PlayStoreRelease> = emptyList(),
    private val failure: Exception? = null
) : PlayPublisher {
    val deployCalls = mutableListOf<DeployRequest>()
    val promoteCalls = mutableListOf<PromoteRequest>()
    val notesCalls = mutableListOf<UpdateReleaseNotesRequest>()
    val listCalls = mutableListOf<String>()

    override fun listReleases(packageName: String): List<PlayStoreRelease> {
        listCalls += packageName
        failure?.let { throw it }
        return releases
    }

    override fun deploy(request: DeployRequest): PlayStoreDeploymentResult {
        deployCalls += request
        failure?.let { throw it }
        return deployResult ?: PlayStoreDeploymentResult(true, "edit-1", request.packageName, request.track, 141, "ok")
    }

    override fun promote(request: PromoteRequest): PlayStoreDeploymentResult {
        promoteCalls += request
        failure?.let { throw it }
        return PlayStoreDeploymentResult(true, "edit-2", request.packageName, request.toTrack, request.versionCode, "ok")
    }

    override fun updateReleaseNotes(request: UpdateReleaseNotesRequest): PlayStoreDeploymentResult {
        notesCalls += request
        failure?.let { throw it }
        return PlayStoreDeploymentResult(true, "edit-3", request.packageName, request.track, request.versionCode, "ok")
    }
}
