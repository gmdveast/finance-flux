package com.gmdveast.financeflux.ipc;

/** Synthetic-only fixture for testing the public connector IPC. */
interface IFinanceFluxConnector {
    String getManifestJson();
    String listCandidatesJson(String profileId, int limit);
    void acknowledgeCandidate(String profileId, String candidateId);
}
