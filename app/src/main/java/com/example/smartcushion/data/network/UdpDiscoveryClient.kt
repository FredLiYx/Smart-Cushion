package com.example.smartcushion.data.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpDiscoveryClient(private val context: Context) {
    suspend fun listenOnce(timeoutMs: Int = 600): String? = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("smart-cushion-discovery")
        multicastLock.setReferenceCounted(false)

        try {
            multicastLock.acquire()
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = timeoutMs
                socket.bind(InetSocketAddress(DISCOVERY_PORT))

                val buffer = ByteArray(128)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val payload = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (payload == DISCOVERY_PAYLOAD) packet.address.hostAddress else null
            }
        } catch (_: Exception) {
            null
        } finally {
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }

    companion object {
        private const val DISCOVERY_PORT = 4210
        private const val DISCOVERY_PAYLOAD = "ESP32_CUSHION"
    }
}
