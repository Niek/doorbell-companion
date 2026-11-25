package nl.niek.doorbellcompanion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

data class DiscoveredHomeAssistant(
    val name: String,
    val url: String,
    val version: String?,
    val internalUrl: String? = null,
    val externalUrl: String? = null,
)

class HomeAssistantDiscovery(
    context: Context,
    private val onChanged: (List<DiscoveredHomeAssistant>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val discovered = linkedMapOf<String, DiscoveredHomeAssistant>()
    @Volatile var active = false
        private set

    @Synchronized
    fun start() {
        if (active || nsd == null) return
        active = true
        discovered.clear()
        try {
            multicastLock = appContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("doorbell_companion_ha_mdns")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {}

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("DoorbellCompanion", "mDNS discovery start failed: $errorCode")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w("DoorbellCompanion", "mDNS discoverServices error: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        if (!active) return
        resolveQueue.addLast(info)
        pumpResolveQueue()
    }

    @Synchronized
    private fun pumpResolveQueue() {
        if (!active || resolving || nsd == null) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@HomeAssistantDiscovery) {
                        resolving = false
                        pumpResolveQueue()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    handleResolved(resolved)
                    synchronized(this@HomeAssistantDiscovery) {
                        resolving = false
                        pumpResolveQueue()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
            pumpResolveQueue()
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val attrs = info.attributes
        fun txt(key: String): String? =
            attrs[key]?.let { String(it, Charsets.UTF_8).trim() }?.takeIf { it.isNotBlank() }

        val locationName = txt("location_name") ?: info.serviceName ?: "Home Assistant"
        val version = txt("version")
        @Suppress("DEPRECATION")
        val hostAddr = info.host?.hostAddress
        val fallbackLocal = if (!hostAddr.isNullOrBlank() && info.port > 0) {
            "http://$hostAddr:${info.port}"
        } else null

        val externalUrl = txt("external_url")?.trimEnd('/')
        val txtInternal = txt("internal_url")?.trimEnd('/')
        // Prefer the resolved numeric IP over http://*.local to skip per-connection mDNS lookup latency.
        val internalUrl = if (txtInternal != null && txtInternal.startsWith("http://", ignoreCase = true) &&
            txtInternal.contains(".local", ignoreCase = true) && fallbackLocal != null
        ) {
            fallbackLocal
        } else {
            (txtInternal ?: fallbackLocal)?.trimEnd('/')
        }
        // Prefer external_url as primary so doorbell calls keep working remotely on 5G,
        // while keeping internalUrl for low-latency LAN racing on Wi-Fi.
        val chosenUrl = (externalUrl ?: txt("base_url")?.trimEnd('/') ?: internalUrl) ?: return

        val entry = DiscoveredHomeAssistant(locationName, chosenUrl, version, internalUrl, externalUrl)
        val snapshot = synchronized(this) {
            if (!active) return
            discovered[chosenUrl] = entry
            discovered.values.toList()
        }
        main.post { onChanged(snapshot) }
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        resolveQueue.clear()
        discoveryListener?.let {
            try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }
}
