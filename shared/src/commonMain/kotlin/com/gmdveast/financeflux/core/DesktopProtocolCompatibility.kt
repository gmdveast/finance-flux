package com.gmdveast.financeflux.core

enum class DesktopProtocolProblem(val errorCode: String) {
    MISSING("desktop_protocol_missing"),
    INVALID("desktop_protocol_invalid"),
    UNSUPPORTED_SCHEMA("desktop_protocol_schema_unsupported"),
    WRONG_APPLICATION("desktop_protocol_application_mismatch"),
    OPERATION_SCHEMA_UNSUPPORTED("desktop_operation_schema_2_unsupported"),
    CAPABILITY_MISSING("desktop_automatic_review_unsupported"),
}

data class DesktopProtocolCompatibility(
    val problem: DesktopProtocolProblem? = null,
) {
    val compatible: Boolean get() = problem == null
}

object DesktopProtocolCompatibilityCheck {
    const val REQUIRED_OPERATION_SCHEMA = 2
    const val REQUIRED_CAPABILITY = "automatic-operation-review.v1"

    fun validate(protocol: DesktopProtocolEnvelope?): DesktopProtocolCompatibility {
        if (protocol == null) return DesktopProtocolCompatibility(DesktopProtocolProblem.MISSING)
        if (protocol.schema != 1) return DesktopProtocolCompatibility(DesktopProtocolProblem.UNSUPPORTED_SCHEMA)
        if (protocol.application != "finance-desktop") return DesktopProtocolCompatibility(DesktopProtocolProblem.WRONG_APPLICATION)
        if (protocol.version.isBlank() || protocol.version.length > 64) {
            return DesktopProtocolCompatibility(DesktopProtocolProblem.INVALID)
        }
        if (protocol.fileSchemas.values.any { versions ->
                versions.isEmpty() || versions.any { it < 1 } || versions.distinct().size != versions.size
            } || protocol.capabilities.size > 64 || protocol.capabilities.any { it.isBlank() || it.length > 100 }
        ) {
            return DesktopProtocolCompatibility(DesktopProtocolProblem.INVALID)
        }
        if (REQUIRED_OPERATION_SCHEMA !in protocol.fileSchemas["operation"].orEmpty()) {
            return DesktopProtocolCompatibility(DesktopProtocolProblem.OPERATION_SCHEMA_UNSUPPORTED)
        }
        if (REQUIRED_CAPABILITY !in protocol.capabilities) {
            return DesktopProtocolCompatibility(DesktopProtocolProblem.CAPABILITY_MISSING)
        }
        return DesktopProtocolCompatibility()
    }
}
