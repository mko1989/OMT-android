package com.omt.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.InetAddress

/**
 * Registers this device as an OMT source on the local network using DNS-SD (mDNS).
 * OMT uses service type "_omt._tcp" and instance name "HOSTNAME (Source Name)".
 * vMix and other OMT receivers discover sources via mDNS.
 */
class OmtDiscoveryRegistration(
    private val context: Context,
    private val sourceName: String = "Android (OMT Camera)",
    private val onRegistered: ((String) -> Unit)? = null,
    private val onRegistrationFailed: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "OmtDiscovery"
        /** OMT DNS-SD service type (RFC 6763). Trailing dot for full DNS-SD compatibility with OMT Viewer and vMix. */
        const val OMT_SERVICE_TYPE = "_omt._tcp."
    }

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var serviceInfo: NsdServiceInfo? = null

    /**
     * Register as an OMT source. Call when the TCP server is already listening on [port].
     * [hostAddress] optional device IP (e.g. WiFi) so resolvers get the correct address.
     */
    fun register(port: Int, hostAddress: String? = null) {
        val manager = nsdManager
        if (manager == null) {
            onRegistrationFailed?.invoke("NSD not available")
            return
        }
        unregister()
        // OMT format: "HOSTNAME (Source Name)" for instance name. vMix discovers via mDNS.
        val instanceName = if (sourceName.contains("(") && sourceName.contains(")")) {
            sourceName
        } else {
            val host = (Build.MODEL ?: "Android").take(30).replace(Regex("[^\\w\\s-]"), "")
                .trim().takeIf { it.isNotBlank() } ?: "OMT"
            "$host ($sourceName)"
        }
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = OMT_SERVICE_TYPE
            setPort(port)
            hostAddress?.takeIf { it.isNotBlank() }?.let { addr ->
                try {
                    setHost(InetAddress.getByName(addr))
                    Log.d(TAG, "Advertising at $addr:$port")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set host $addr", e)
                }
            }
        }
        serviceInfo = info
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo?) {
                Log.d(TAG, "OMT discovery registered: ${registered?.serviceName} port $port")
                onRegistered?.invoke(registered?.serviceName ?: sourceName)
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "OMT discovery registration failed: $errorCode")
                onRegistrationFailed?.invoke("Registration failed (code $errorCode)")
            }
            override fun onServiceUnregistered(arg: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "OMT discovery unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "registerService failed", e)
            onRegistrationFailed?.invoke(e.message ?: "Unknown error")
        }
    }

    fun unregister() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        try {
            manager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService failed", e)
        }
        registrationListener = null
        serviceInfo = null
    }
}
