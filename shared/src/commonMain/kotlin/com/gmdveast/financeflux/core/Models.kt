package com.gmdveast.financeflux.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AutomaticOperationEvent(
    val schema: Int,
    val id: String,
    val author: String,
    val createdAt: String,
    val automation: AutomationOrigin,
    val operation: FinanceOperation,
)

@Serializable
data class AutomationOrigin(
    val connectorId: String,
    val profileId: String,
)

@Serializable
data class FinanceOperation(
    val date: String,
    val type: OperationType,
    val amount: Double,
    val currency: String,
    val accountId: Int,
    val category: String? = null,
    val rate: Double? = null,
    val necessity: String? = null,
    val notes: String? = null,
    val destinationAccountId: Int? = null,
    val destinationAmount: Double? = null,
)

@Serializable
enum class OperationType {
    @SerialName("Доход") INCOME,
    @SerialName("Расход") EXPENSE,
    @SerialName("Перевод") TRANSFER,
}

@Serializable
data class ConnectorProfile(
    val id: String,
    val connectorId: String,
    val packageName: String,
    val name: String,
    val accountId: Int,
    val defaultExpenseCategory: String? = null,
    val defaultIncomeCategory: String? = null,
    val enabled: Boolean,
)

@Serializable
data class ConnectorManifest(
    val schema: Int,
    val connectorId: String,
    val displayName: String,
    val minIpcVersion: Int,
    val maxIpcVersion: Int,
    val capabilities: List<String>,
)

@Serializable
data class ConnectorCandidate(
    val candidateId: String,
    val date: String,
    val type: OperationType,
    val amount: Double,
    val currency: String,
    val notes: String? = null,
    val destinationAccountId: Int? = null,
    val destinationAmount: Double? = null,
)

@Serializable
data class ReferenceListsEnvelope(
    val schema: Int,
    val lists: FinanceReferenceLists,
)

@Serializable
data class FinanceReferenceLists(
    val categories: List<ReferenceCategory> = emptyList(),
    val currencies: List<String> = emptyList(),
    val necessities: List<String> = emptyList(),
    val operationTypes: List<String> = emptyList(),
    val accounts: List<ReferenceAccount> = emptyList(),
)

@Serializable
data class ReferenceCategory(
    val name: String,
    val type: String,
    val emoji: String? = null,
)

@Serializable
data class ReferenceAccount(
    val id: Int,
    val name: String,
    val currency: String,
)

@Serializable
data class DesktopProtocolEnvelope(
    val schema: Int,
    val application: String,
    val version: String,
    val fileSchemas: Map<String, List<Int>>,
    val capabilities: List<String>,
    val generatedAt: String? = null,
)

@Serializable
data class ResetSnapshot(
    val schema: Int,
    val cutoff: String,
    val ids: List<String>,
)

object FinanceJson {
    private val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    private val referenceListsJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val desktopProtocolJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    fun encodeEvent(event: AutomaticOperationEvent): String = strict.encodeToString(event)

    fun decodeEvent(value: String): AutomaticOperationEvent = strict.decodeFromString(value)

    fun decodeManifest(value: String): ConnectorManifest = strict.decodeFromString(value)

    fun decodeCandidates(value: String): List<ConnectorCandidate> = strict.decodeFromString(value)

    fun decodeReferenceLists(value: String): ReferenceListsEnvelope = referenceListsJson.decodeFromString(value)

    fun decodeDesktopProtocol(value: String): DesktopProtocolEnvelope = desktopProtocolJson.decodeFromString(value)

    fun decodeReset(value: String): ResetSnapshot = referenceListsJson.decodeFromString(value)
}
