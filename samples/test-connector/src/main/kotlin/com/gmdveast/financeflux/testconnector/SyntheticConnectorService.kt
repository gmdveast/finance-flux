package com.gmdveast.financeflux.testconnector

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.gmdveast.financeflux.ipc.IFinanceFluxConnector

/** Returns one fictional operation. This service never reads SMS, notifications, or account data. */
class SyntheticConnectorService : Service() {
    private val binder = object : IFinanceFluxConnector.Stub() {
        override fun getManifestJson(): String = MANIFEST_JSON

        override fun listCandidatesJson(profileId: String?, limit: Int): String {
            if (profileId.isNullOrBlank() || limit <= 0) return "[]"
            val acknowledged = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(ackKey(profileId), false)
            return if (acknowledged) "[]" else "[$CANDIDATE_JSON]"
        }

        override fun acknowledgeCandidate(profileId: String?, candidateId: String?) {
            if (!profileId.isNullOrBlank() && candidateId == CANDIDATE_ID) {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(ackKey(profileId), true)
                    .apply()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun ackKey(profileId: String): String = "ack:$profileId:$CANDIDATE_ID"

    private companion object {
        const val PREFERENCES = "synthetic-candidates"
        const val CONNECTOR_ID = "finance-flux.test-connector"
        const val CANDIDATE_ID = "synthetic-test-2026-0001"
        const val MANIFEST_JSON = """{"schema":1,"connectorId":"$CONNECTOR_ID","displayName":"Finance Flux Test Connector","minIpcVersion":1,"maxIpcVersion":1,"capabilities":["expense"]}"""
        const val CANDIDATE_JSON = """{"candidateId":"$CANDIDATE_ID","date":"2026-01-15","type":"Расход","amount":12.34,"currency":"RUB","notes":"Синтетическая тестовая операция. Не реальная банковская транзакция."}"""
    }
}
