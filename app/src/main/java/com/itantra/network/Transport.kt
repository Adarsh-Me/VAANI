package com.itantra.network

import com.itantra.models.ConnectionState
import com.itantra.models.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Transport abstraction: BLE GATT (Tier 0) or in-process loopback (demo/tests). */
interface Transport {
    val name: String
    val state: StateFlow<ConnectionState>
    fun start(onPacket: (Packet) -> Unit)
    fun stop()
    fun send(packet: Packet): Boolean
}

/**
 * Single-device demo transport: delivers to the local receiver after a
 * short delay that simulates one BLE hop (~100 ms).
 */
class LoopbackTransport : Transport {
    override val name = "Loopback"
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state
    private var scope: CoroutineScope? = null
    private var listener: ((Packet) -> Unit)? = null

    override fun start(onPacket: (Packet) -> Unit) {
        listener = onPacket
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _state.value = ConnectionState.CONNECTED
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        listener = null
        _state.value = ConnectionState.DISCONNECTED
    }

    override fun send(packet: Packet): Boolean {
        val l = listener ?: return false
        scope?.launch {
            delay(100)
            // Loopback delivers the exact wire bytes (validates codec too).
            Packet.deserialize(packet.serialize())?.let { l(it) }
        }
        return true
    }
}

object TransportFactory {
    fun create(
        type: TransportType,
        bleServer: (() -> Transport)? = null,
        bleClient: (() -> Transport)? = null
    ): Transport = when (type) {
        TransportType.LOOPBACK -> LoopbackTransport()
        TransportType.BLE -> (bleServer?.invoke() ?: bleClient?.invoke()) ?: LoopbackTransport()
    }
}
