package com.omt.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Browses the local network for OMT sources via mDNS/DNS-SD.
 * Discovers services of type `_omt._tcp` and resolves their host/port.
 */
class OmtSourceBrowser(
    context: Context,
    private val onSourceFound: (OmtSource) -> Unit,
    private val onSourceLost: (String) -> Unit
) {
    data class OmtSource(
        val name: String,
        val host: String,
        val port: Int
    ) {
        override fun toString() = "$name ($host:$port)"
    }

    companion object {
        private const val TAG = "OmtSourceBrowser"
        private const val SERVICE_TYPE = "_omt._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var browsing = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "Discovery started for $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
            try {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val port = info.port
                        Log.i(TAG, "Resolved: ${info.serviceName} → $host:$port")
                        onSourceFound(OmtSource(info.serviceName, host, port))
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "resolveService error: ${e.message}")
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
            onSourceLost(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: error $errorCode")
            browsing = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: error $errorCode")
        }
    }

    fun start() {
        if (browsing) return
        browsing = true
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        if (!browsing) return
        browsing = false
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery: ${e.message}")
        }
    }
}
