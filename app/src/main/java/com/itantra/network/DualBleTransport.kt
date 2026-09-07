package com.itantra.network

import android.content.Context
import com.itantra.models.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Both BLE roles at once: advertise as peripheral (BleServer) and scan as
 * central (BleClient). On a two-phone link each side runs this, so whichever
 * role wins first forms the GATT connection — no "both advertise, nobody
 * scans" deadlock. Send goes through whichever transport currently has a peer.
 */
class DualBleTransport(private val context: Context) : Transport {
    override val name = "BLE-Dual"
    private val server = BleServer(context)
    private val client = BleClient(context)
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state

    private var scope: CoroutineScope? = null

    override fun start(onPacket: (Packet) -> Unit) {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        server.start(onPacket)
        client.start(onPacket)
        s.launch {
            while (true) {
                val sv = server.state.value
                val cv = client.state.value
                _state.value = when {
                    sv == ConnectionState.CONNECTED || cv == ConnectionState.CONNECTED ->
                        ConnectionState.CONNECTED
                    sv == ConnectionState.ADVERTISING || cv == ConnectionState.SCANNING ||
                        cv == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                    else -> ConnectionState.DISCONNECTED
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    override fun send(packet: Packet): Boolean =
        server.send(packet) || client.send(packet)

    override fun stop() {
        scope?.cancel()
        scope = null
        server.stop()
        client.stop()
        _state.value = ConnectionState.DISCONNECTED
    }
}
