package com.gmdveast.financeflux.connectors

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import java.security.MessageDigest

data class DiscoveredConnectorService(
    val component: ComponentName,
    val packageName: String,
    val displayName: String,
    val signingFingerprint: String,
    val trusted: Boolean,
)

class ConnectorTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("trusted-connectors", Context.MODE_PRIVATE)

    fun fingerprint(packageName: String): String? = preferences.getString("sha256:$packageName", null)

    fun trust(packageName: String, fingerprint: String): Boolean = preferences.edit()
        .putString("sha256:$packageName", fingerprint)
        .commit()

    fun revoke(packageName: String) {
        preferences.edit().remove("sha256:$packageName").commit()
    }

    fun isTrusted(packageName: String, fingerprint: String): Boolean = fingerprint(packageName) == fingerprint
}

object ConnectorDiscovery {
    const val ACTION = "com.gmdveast.financeflux.CONNECTOR_SERVICE"

    fun discover(context: Context): List<DiscoveredConnectorService> {
        val intent = Intent(ACTION)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.exported && it.packageName != context.packageName }
            .mapNotNull { service -> service.toDiscoveredConnector(context) }
            .distinctBy { it.packageName to it.component.className }
            .sortedBy { it.displayName.lowercase() }
    }

    fun signerFingerprint(context: Context, packageName: String): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val signers = signatures?.takeIf { it.isNotEmpty() } ?: return null
        signers.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }.sorted().joinToString(".")
    }.getOrNull()

    private fun ServiceInfo.toDiscoveredConnector(context: Context): DiscoveredConnectorService? {
        val fingerprint = signerFingerprint(context, packageName) ?: return null
        val label = runCatching {
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
        return DiscoveredConnectorService(
            component = ComponentName(packageName, name),
            packageName = packageName,
            displayName = label,
            signingFingerprint = fingerprint,
            trusted = ConnectorTrustStore(context).isTrusted(packageName, fingerprint),
        )
    }
}
