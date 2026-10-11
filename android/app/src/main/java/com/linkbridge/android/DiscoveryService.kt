package com.linkbridge.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress

class DiscoveryService(
    context: Context,
    private val identity: DeviceIdentity,
    private val port: Int,
    private val onPeersChanged: (List<DiscoveredPeer>) -> Unit = {}
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peers = linkedMapOf<String, DiscoveredPeer>()
    private val resolvingServices = mutableSetOf<String>()
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private var isStarted = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Registered ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Registration failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Unregistered ${serviceInfo.serviceName}")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Unregistration failed: $errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "Discovery started")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType == SERVICE_TYPE) {
                Log.i(TAG, "Found peer: ${serviceInfo.serviceName}")
                resolve(serviceInfo)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Lost peer: ${serviceInfo.serviceName}")
            peers.remove(serviceInfo.serviceName)
            publishPeers()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: $errorCode")
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = identity.deviceName
            serviceType = SERVICE_TYPE
            port = this@DiscoveryService.port
            setAttribute("deviceId", identity.deviceId)
            setAttribute("deviceName", identity.deviceName)
            setAttribute("platform", identity.platform)
            // Protocol 2: transfers use TLS-PSK on tlsPort (the receive server is TLS-only).
            setAttribute("protocol", PROTOCOL_VERSION.toString())
            setAttribute("tlsPort", this@DiscoveryService.port.toString())
            setAttribute("features", identity.features.joinToString(","))
        }

        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            Log.e(TAG, "Could not register service", it)
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.e(TAG, "Could not start discovery", it)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        peers.clear()
        resolvingServices.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.values.forEach { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
        }
        infoCallbacks.clear()
        publishPeers()

        runCatching { nsdManager.unregisterService(registrationListener) }
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watch(serviceInfo)
            return
        }
        if (!resolvingServices.add(serviceInfo.serviceName)) return

        @Suppress("DEPRECATION")
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolvingServices.remove(serviceInfo.serviceName)
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                resolvingServices.remove(resolvedService.serviceName)

                val peer = resolvedService.toPeer() ?: return
                if (peer.deviceId == identity.deviceId) return

                peers[peer.serviceName] = peer
                publishPeers()
            }
        })
    }

    /**
     * API 34+: follow the service instead of resolving once, so a Mac that restarts on a
     * different port (it falls back when 49152 is taken) is picked up without rescanning.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun watch(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        if (infoCallbacks.containsKey(name)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                infoCallbacks.remove(name)
                Log.e(TAG, "Watch failed for $name: $errorCode")
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val peer = updated.toPeer() ?: return
                if (peer.deviceId == identity.deviceId) return
                peers[name] = peer
                publishPeers()
            }

            override fun onServiceLost() {
                peers.remove(name)
                publishPeers()
            }

            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks[name] = callback
        runCatching { nsdManager.registerServiceInfoCallback(serviceInfo, mainHandler::post, callback) }
            .onFailure { infoCallbacks.remove(name); Log.e(TAG, "Could not watch $name", it) }
    }

    private fun NsdServiceInfo.toPeer(): DiscoveredPeer? {
        val hostAddress = hostAddress() ?: return null
        val deviceId = attributes["deviceId"]?.decodeToString()
        val deviceName = attributes["deviceName"]?.decodeToString()?.ifBlank { serviceName } ?: serviceName
        val platform = attributes["platform"]?.decodeToString()
        val tlsPort = attributes["tlsPort"]?.decodeToString()?.toIntOrNull()

        return DiscoveredPeer(
            serviceName = serviceName,
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform,
            host = hostAddress.hostAddress ?: return null,
            port = port,
            tlsPort = tlsPort
        )
    }

    private fun NsdServiceInfo.hostAddress(): InetAddress? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            host
        }
    }

    private fun publishPeers() {
        val snapshot = peers.values.sortedWith(compareBy({ it.platform ?: "" }, { it.deviceName }))
        mainHandler.post { onPeersChanged(snapshot) }
    }

    companion object {
        private const val TAG = "LinkBridgeDiscovery"
        private const val SERVICE_TYPE = "_linkbridge._tcp."
        const val PROTOCOL_VERSION = 2
    }
}
