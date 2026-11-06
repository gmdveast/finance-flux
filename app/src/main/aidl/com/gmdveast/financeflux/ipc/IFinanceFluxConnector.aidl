package com.gmdveast.financeflux.ipc;

/**
 * Finance Flux connector IPC v1. Methods return bounded UTF-8 JSON strings.
 * Implementations must not return raw SMS or unrelated personal content.
 */
interface IFinanceFluxConnector {
    String getManifestJson();
    String listCandidatesJson(String profileId, int limit);
    void acknowledgeCandidate(String profileId, String candidateId);
}
