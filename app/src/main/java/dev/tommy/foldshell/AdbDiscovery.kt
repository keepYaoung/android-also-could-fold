package dev.tommy.foldshell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface

/** Discover only this phone's endpoints. Every actual connection uses loopback. */
class AdbDiscovery(context: Context, private val found: (Boolean, Int) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val pending = ArrayDeque<Triple<Int, Boolean, NsdServiceInfo>>()
    private var generation = 0
    private var resolving = false
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (listeners.isNotEmpty()) return
        val current = ++generation
        for (pairing in listOf(false, true)) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    main.post { if (current == generation) stop() }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        if (current == generation) {
                            pending.add(Triple(current, pairing, info))
                            resolveNext()
                        }
                    }
                }
            }
            listeners.add(listener)
            try { nsd.discoverServices(if (pairing) "_adb-tls-pairing._tcp." else "_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: Exception) { stop(); return }
        }
    }
    // Legacy NSD allows only one resolution at a time on some Android versions.
    private fun resolveNext() {
        if (resolving || pending.isEmpty()) return
        val (current, pairing, info) = pending.removeFirst()
        resolving = true
        fun finish(result: NsdServiceInfo?) {
            main.post {
                resolving = false
                if (current == generation && result != null) {
                    val address = result.host
                    val local = try { address != null && (address.isLoopbackAddress || NetworkInterface.getByInetAddress(address) != null) }
                        catch (_: Exception) { false }
                    if (local && result.port in 1..65535) found(pairing, result.port)
                }
                resolveNext()
            }
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = finish(null)
                override fun onServiceResolved(info: NsdServiceInfo) = finish(info)
            })
        } catch (_: Exception) { finish(null) }
    }
    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        pending.clear()
        val old = listeners.toList(); listeners.clear()
        old.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
    }
}
