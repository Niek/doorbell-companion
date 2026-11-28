package nl.niek.doorbellcompanion

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Confirms that a plain-HTTP address is the signed-in Home Assistant before it receives credentials.
 * Home Assistant's mDNS service record includes its instance ID, so a direct mDNS query to the address
 * must return `uuid=<instance ID>`. Another device that happens to use the same address can't do that.
 */
object LocalHomeAssistant {
    private const val CACHE_MS = 30_000L
    private val verifiedAt = ConcurrentHashMap<String, Long>()
    private val serviceName = listOf("_home-assistant", "_tcp", "local")
        .fold(byteArrayOf()) { name, label -> name + label.length.toByte() + label.toByteArray() } + 0.toByte()

    fun isVerified(host: String, instanceId: String): Boolean {
        if (instanceId.isBlank()) return false
        val key = "$host/$instanceId"
        val now = SystemClock.elapsedRealtime()
        if (verifiedAt[key]?.let { now - it < CACHE_MS } == true) return true
        val verified = try {
            query(InetAddress.getByName(host), "uuid=$instanceId".toByteArray())
        } catch (_: Exception) {
            false
        }
        if (verified) verifiedAt[key] = now
        return verified
    }

    private fun query(address: InetAddress, expected: ByteArray): Boolean = DatagramSocket().use { socket ->
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
