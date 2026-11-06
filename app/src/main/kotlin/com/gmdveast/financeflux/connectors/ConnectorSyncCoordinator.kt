package com.gmdveast.financeflux.connectors

import android.content.Context
import com.gmdveast.financeflux.core.CandidateNormalizer
import com.gmdveast.financeflux.core.ConnectorProfile
import com.gmdveast.financeflux.core.DesktopProtocolProblem
import com.gmdveast.financeflux.core.FinanceJson
import com.gmdveast.financeflux.core.FinanceReferenceLists
import com.gmdveast.financeflux.core.QueuePublisher
import com.gmdveast.financeflux.data.ProfileRepository
import com.gmdveast.financeflux.storage.QueueArchiver
import com.gmdveast.financeflux.storage.ResetProcessor
import com.gmdveast.financeflux.storage.SafVaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConnectorSyncSummary(
    val processed: Int,
    val discardedByReset: Int,
    val errors: List<String>,
)

class ConnectorSyncCoordinator(
    private val context: Context,
    private val vault: SafVaultStore,
    private val lists: FinanceReferenceLists,
    private val profiles: ProfileRepository,
    private val trustStore: ConnectorTrustStore,
) {
    private val binder = BoundConnectorClient(context)
    private val publisher = QueuePublisher(vault)

    suspend fun sync(services: List<DiscoveredConnectorService>): ConnectorSyncSummary {
        var processed = 0
        var discardedByReset = 0
        val errors = mutableListOf<String>()
        val activeProfiles = profiles.loadProfiles().filter(ConnectorProfile::enabled)
        val resetProcessor = ResetProcessor(vault)
        val resetOutcome = withContext(Dispatchers.IO) { resetProcessor.apply() }
        val protocolCompatibility = withContext(Dispatchers.IO) { vault.readDesktopProtocolCompatibility() }
        if (!protocolCompatibility.compatible) {
            errors += protocolCompatibility.problem?.errorCode ?: DesktopProtocolProblem.INVALID.errorCode
            return ConnectorSyncSummary(0, resetOutcome.deletedQueueFiles, errors)
        }

        for (service in services.filter(DiscoveredConnectorService::trusted)) {
            val currentFingerprint = ConnectorDiscovery.signerFingerprint(context, service.packageName)
            if (currentFingerprint == null || !trustStore.isTrusted(service.packageName, currentFingerprint)) {
                errors += "connector_signer_changed"
                continue
            }
            val connectorProfiles = activeProfiles.filter { it.packageName == service.packageName }
            if (connectorProfiles.isEmpty()) continue

            for (profile in connectorProfiles) {
                try {
                    val count = binder.withConnector(service.component) { api ->
                        val manifestJson = api.getManifestJson()
                        require(manifestJson.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "connector_manifest_too_large" }
                        val manifest = FinanceJson.decodeManifest(manifestJson)
                        require(manifest.schema == 1 && manifest.connectorId == profile.connectorId) { "connector_manifest_mismatch" }
                        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 100) { "connector_manifest_invalid" }
                        require(manifest.minIpcVersion >= 1 && manifest.maxIpcVersion >= manifest.minIpcVersion) { "connector_manifest_version_invalid" }
                        require(manifest.capabilities.size <= 3 && manifest.capabilities.distinct().size == manifest.capabilities.size &&
                            manifest.capabilities.all { it in setOf("income", "expense", "transfer") }) { "connector_manifest_capability_invalid" }
                        require(manifest.minIpcVersion <= IPC_VERSION && manifest.maxIpcVersion >= IPC_VERSION) { "connector_ipc_unsupported" }

                        val candidatesJson = api.listCandidatesJson(profile.id, MAX_CANDIDATES)
                        require(candidatesJson.toByteArray(Charsets.UTF_8).size <= MAX_CANDIDATE_BYTES) { "connector_response_too_large" }
                        val candidates = FinanceJson.decodeCandidates(candidatesJson)
                        require(candidates.size <= MAX_CANDIDATES) { "too_many_connector_candidates" }
                        var accepted = 0
                        val normalizer = CandidateNormalizer(lists)

                        for (candidate in candidates) {
                            try {
                                val seed = profiles.eventSeed(profile.connectorId, profile.id, candidate.candidateId)
                                    ?: throw IllegalStateException("candidate_identity_store_failed")
                                if (resetProcessor.shouldDiscard(seed.eventId, seed.createdAt, resetOutcome.snapshot)) {
                                    api.acknowledgeCandidate(profile.id, candidate.candidateId)
                                    discardedByReset++
                                    continue
                                }
                                val event = normalizer.toEvent(candidate, profile, seed.eventId, seed.createdAt)
                                val result = publisher.publish(event, lists)
                                if (!result.queued) {
                                    errors += result.message
                                    continue
                                }
                                api.acknowledgeCandidate(profile.id, candidate.candidateId)
                                accepted++
                            } catch (_: Exception) {
                                errors += "candidate_rejected_or_not_acknowledged"
                            }
                        }
                        accepted
                    }
                    processed += count
                } catch (_: Exception) {
                    errors += "connector_sync_failed"
                }
            }
        }

        withContext(Dispatchers.IO) {
            runCatching { QueueArchiver(vault).archiveCardsForMissingQueueFiles() }
        }
        return ConnectorSyncSummary(processed, discardedByReset, errors.distinct())
    }

    companion object {
        const val IPC_VERSION = 1
        const val MAX_CANDIDATES = 50
        const val MAX_MANIFEST_BYTES = 8 * 1024
        const val MAX_CANDIDATE_BYTES = 64 * 1024
    }
}
