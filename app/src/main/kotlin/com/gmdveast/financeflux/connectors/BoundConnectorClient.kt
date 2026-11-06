package com.gmdveast.financeflux.connectors

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.gmdveast.financeflux.ipc.IFinanceFluxConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BoundConnectorClient(private val context: Context) {
    suspend fun <T> withConnector(component: ComponentName, action: suspend (IFinanceFluxConnector) -> T): T {
        val connector = withTimeout(10_000) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<BoundService> { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val api = IFinanceFluxConnector.Stub.asInterface(binder)
                        if (continuation.isActive) continuation.resume(BoundService(api, this))
                    }

                    override fun onServiceDisconnected(name: ComponentName) = Unit

                    override fun onBindingDied(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("connector_binding_died"))
                    }

                    override fun onNullBinding(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("connector_null_binding"))
                    }
                }
                val intent = Intent(ConnectorDiscovery.ACTION).setComponent(component)
                val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                if (!bound) {
                    continuation.resumeWithException(IllegalStateException("connector_bind_failed"))
                } else {
                    continuation.invokeOnCancellation { context.unbindService(connection) }
                }
                }
            }
        }

        try {
            return withContext(Dispatchers.IO) { action(connector.api) }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                runCatching { context.unbindService(connector.connection) }
            }
        }
    }

    private data class BoundService(
        val api: IFinanceFluxConnector,
        val connection: ServiceConnection,
    )
}
