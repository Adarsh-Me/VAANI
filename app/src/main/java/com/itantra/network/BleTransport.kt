package com.itantra.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.itantra.models.ConnectionState
import com.itantra.models.PacketLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val SVC = UUID.fromString(com.itantra.models.BleUuids.SERVICE)
private val TX = UUID.fromString(com.itantra.models.BleUuids.TX_CHAR)
private val RX = UUID.fromString(com.itantra.models.BleUuids.RX_CHAR)
private val CCCD = UUID.fromString(com.itantra.models.BleUuids.CCCD)

private fun adapterOf(ctx: Context): BluetoothAdapter? =
    (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/**
 * BLE peripheral role: advertises the iTantra service, accepts writes on RX,
 * notifies connected centrals on TX. Satisfies the Tier-0 GATT requirement.
 */
@SuppressLint("MissingPermission")
class BleServer(private val context: Context) : Transport {
    override val name = "BLE-Peripheral"
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val peers = ConcurrentHashMap<String, BluetoothDevice>()
    private val reassembler = PacketChunker.Reassembler()
    private var listener: ((Packet) -> Unit)? = null
    private var mtuPayload = PacketLimits.MAX_SIZE_BYTES

    private val advertiser get() = adapterOf(context)?.bluetoothLeAdvertiser

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = ConnectionState.ADVERTISING
        }
        override fun onStartFailure(errorCode: Int) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peers[device.address] = device
                _state.value = ConnectionState.CONNECTED
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers.remove(device.address)
                if (peers.isEmpty()) _state.value = ConnectionState.ADVERTISING
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == RX) {
                reassembler.feed(value)?.let { full ->
                    Packet.deserialize(full)?.let { listener?.invoke(it) }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuPayload = (mtu - 3).coerceIn(20, PacketLimits.MAX_SIZE_BYTES)
        }
    }

    override fun start(onPacket: (Packet) -> Unit) {
        listener = onPacket
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            gattServer = manager.openGattServer(context, serverCallback)
            val service = BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            txChar = BluetoothGattCharacteristic(
                TX,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).also {
                it.addDescriptor(
                    BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE)
                )
                service.addCharacteristic(it)
            }
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    RX,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            gattServer?.addService(service)

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SVC))
                .setIncludeDeviceName(true)
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    override fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        peers.clear()
        listener = null
        _state.value = ConnectionState.DISCONNECTED
    }

    override fun send(packet: Packet): Boolean {
        val server = gattServer ?: return false
        if (peers.isEmpty()) return false
        return try {
            var ok = true
            for (frame in PacketChunker.chunk(packet.serialize(), mtuPayload)) {
                val tx = txChar ?: return false
                @Suppress("DEPRECATION")
                tx.value = frame
                for (peer in peers.values) {
                    @Suppress("DEPRECATION")
                    ok = server.notifyCharacteristicChanged(peer, tx, false) && ok
                }
            }
            ok
        } catch (e: SecurityException) {
            false
        }
    }
}

/**
 * BLE central role: scans for the iTantra service, connects, subscribes to
 * TX notifications and writes packets to RX.
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context) : Transport {
    override val name = "BLE-Central"
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val reassembler = PacketChunker.Reassembler()
    private var listener: ((Packet) -> Unit)? = null
    private var mtuPayload = PacketLimits.MAX_SIZE_BYTES

    private val scanner get() = adapterOf(context)?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScanOnly()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = ConnectionState.CONNECTING
                runCatching { g.requestMtu(com.itantra.models.BleUuids.MTU_REQUEST) }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                txChar = null
                rxChar = null
                _state.value = ConnectionState.DISCONNECTED
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuPayload = (mtu - 3).coerceIn(20, PacketLimits.MAX_SIZE_BYTES)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(SVC) ?: run {
                _state.value = ConnectionState.DISCONNECTED
                return
            }
            txChar = svc.getCharacteristic(TX)
            rxChar = svc.getCharacteristic(RX)
            val tx = txChar ?: return
            runCatching {
                g.setCharacteristicNotification(tx, true)
                tx.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            _state.value = ConnectionState.CONNECTED
        }

        // Pre-API-33 path: the 3-arg callback never fires there.
        @Deprecated("legacy")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, value)
        }
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == TX) {
                reassembler.feed(value)?.let { full ->
                    Packet.deserialize(full)?.let { listener?.invoke(it) }
                }
            }
        }
    }

    override fun start(onPacket: (Packet) -> Unit) {
        listener = onPacket
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            _state.value = ConnectionState.SCANNING
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private fun stopScanOnly() {
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun connect(device: BluetoothDevice) {
        try {
            _state.value = ConnectionState.CONNECTING
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    override fun stop() {
        stopScanOnly()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        listener = null
        _state.value = ConnectionState.DISCONNECTED
    }

    override fun send(packet: Packet): Boolean {
        val g = gatt ?: return false
        val rx = rxChar ?: return false
        return try {
            var ok = true
            for (frame in PacketChunker.chunk(packet.serialize(), mtuPayload)) {
                rx.value = frame
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ok = g.writeCharacteristic(rx) && ok
            }
            ok
        } catch (e: SecurityException) {
            false
        }
    }
}
