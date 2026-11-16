package app.hushcast.automute.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DiscoveredDesktopWorker(
    val serviceName: String,
    val host: String,
    val port: Int
) {
    val url: String get() = "http://$host:$port"
}

/**
 * Discovers HushCast Desktop Companion Workers on the local Wi-Fi network via mDNS (_hushcast._tcp.).
 */
object DesktopWorkerDiscovery {
    private const val SERVICE_TYPE = "_hushcast._tcp."

    suspend fun discoverFirst(
        context: Context,
        timeoutMs: Long = 4000L
    ): DiscoveredDesktopWorker? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            var finished = false
            var discoveryListener: NsdManager.DiscoveryListener? = null

            fun finish(result: DiscoveredDesktopWorker?) {
                if (finished) return
                finished = true
                discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                if (cont.isActive) cont.resume(result)
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (finished) return
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = serviceInfo.host?.hostAddress ?: return
                            finish(
                                DiscoveredDesktopWorker(
                                    serviceName = serviceInfo.serviceName,
                                    host = host,
                                    port = serviceInfo.port
                                )
                            )
                        }
                    })
                }

                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finish(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            discoveryListener = listener
            runCatching {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { finish(null) }

            cont.invokeOnCancellation {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }
}
