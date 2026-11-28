package nl.niek.doorbellcompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Confirms that a plain-HTTP address is the signed-in Home Assistant before it receives credentials.
 * Home Assistant's mDNS service record includes its instance ID, so a direct mDNS query to the address
 * must return `uuid=<instance ID>`. Another device that happens to use the same address can't do that.
 * A successful check only counts for the network it ran on.
 */
object LocalHomeAssistant {
    private val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var generation = 0
    @Volatile private var connectivity: ConnectivityManager? = null
    private val serviceName = listOf("_home-assistant", "_tcp", "local")
        .fold(byteArrayOf()) { name, label -> name + label.length.toByte() + label.toByteArray() } + 0.toByte()

    /** Clears checks when the default network changes, then rechecks [targets] (host to instance ID) on the new one. */
    @Synchronized
    fun watch(context: Context, targets: () -> Collection<Pair<String, String>>) {
        if (connectivity != null) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = manager
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current = manager.activeNetwork

            override fun onAvailable(network: Network) {
                // The first callback reports the network the app already uses, so checks in progress stay valid.
                if (network != current) changed(network)
                targets().forEach { (host, instanceId) -> thread { isVerified(host, instanceId) } }
            }

            override fun onLost(network: Network) = changed(null)

            private fun changed(network: Network?) {
                current = network
                generation++
                verified.clear()
            }
        })
    }

    /** Returns whether [host] confirmed [instanceId] on the current network, checking it now if needed. */
    fun isVerified(host: String, instanceId: String): Boolean {
        if (instanceId.isBlank()) return false
        val started = generation
        val network = connectivity?.activeNetwork
        val key = "$network/$host/$instanceId"
        if (key in verified) return true
        val confirmed = try {
            query(network, InetAddress.getByName(host), "uuid=$instanceId".toByteArray())
        } catch (_: Exception) {
            false
        }
        // A check that started before a network change says nothing about the new network.
        if (!confirmed || started != generation) return false
        verified += key
        return true
    }

    private fun query(network: Network?, address: InetAddress, expected: ByteArray): Boolean = DatagramSocket().use { socket ->
        network?.bindSocket(socket)
        socket.connect(address, 5353)
        socket.soTimeout = 400
        val buffer = ByteArray(9000)
        repeat(2) {
            // A legacy unicast query gets a direct reply. A fresh ID keeps a retry from being dropped as a duplicate.
            val id = Random.nextInt(0x10000)
            val packet = byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
                serviceName + byteArrayOf(0, 12, 0, 1) // PTR, IN
            socket.send(DatagramPacket(packet, packet.size))
            try {
                while (true) {
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    if (buffer.contains(reply.length, expected)) return true
                }
            } catch (_: SocketTimeoutException) {
            }
        }
        false
    }

    private fun ByteArray.contains(length: Int, needle: ByteArray): Boolean =
        (0..length - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
