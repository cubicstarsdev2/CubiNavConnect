package com.cubicl.cubinavapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class BleState { OFF, IDLE, CONNECTING, BONDING, CONNECTED, RECONNECTING }

class FoundDevice(val name: String?, val address: String, val rssi: Int)

interface BleListener {
    fun onReady()
    fun onData(d: ByteArray)
    fun onBattery(pct: Int)
    fun onLog(s: String)
}

/**
 * README 6.1: central role. Scan, bond, MTU 247, notifications, ordered write-without-response queue
 * (one RX characteristic for everything), auto-reconnect with backoff. Everything runs on the main looper.
 */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val l: BleListener) {
    companion object {
        val SERVICE: UUID = UUID.fromString("7a8f0001-3c5e-4b1a-9d27-5e1f4a6b7c80")
        val RX: UUID = UUID.fromString("7a8f0002-3c5e-4b1a-9d27-5e1f4a6b7c80")
        val TX: UUID = UUID.fromString("7a8f0003-3c5e-4b1a-9d27-5e1f4a6b7c80")
        val BATT_CHR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val BATT_SVC: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BACKOFF_S = longArrayOf(1, 2, 4, 8, 15)
    }

    val state = MutableStateFlow(BleState.IDLE)
    val scanning = MutableStateFlow(false)
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    var mtu = 23
        private set

    /** Called (on the main looper) whenever a write finished; lets bulk senders such as OTA refill the queue. */
    var onWriteIdle: (() -> Unit)? = null

    /** Writes waiting in the queue, plus the one in flight. */
    fun queued(): Int = queue.size + (if (pendingPose != null) 1 else 0) + (if (writing) 1 else 0)

    /** Drops queued, not yet written commands (used when a bulk transfer rewinds). */
    fun clearQueue() { queue.clear() }

    fun requestFastLink() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var targetAddr: String? = null
    private var wanted = false
    private var ready = false
    private var writing = false
    private var writeSeq = 0
    private var attempt = 0
    private var bondRegistered = false
    private val queue = ArrayDeque<ByteArray>()
    private var pendingPose: ByteArray? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // ---- scanning -----------------------------------------------------------------------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val d = r.device
            if (found.value.none { it.address == d.address })
                found.value = found.value + FoundDevice(r.scanRecord?.deviceName ?: d.name, d.address, r.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            l.onLog("scan failed: $errorCode")
            scanning.value = false
        }
    }

    fun scan() {
        val sc = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || sc == null) { state.value = BleState.OFF; return }
        found.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        sc.startScan(filters, settings, scanCb)
        scanning.value = true
        handler.postDelayed({ stopScan() }, 12_000)
    }

    fun stopScan() {
        if (!scanning.value) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanning.value = false
    }

    // ---- connection ---------------------------------------------------------------------------
    fun connect(address: String) {
        stopScan()
        targetAddr = address
        wanted = true
        attempt = 0
        openGatt()
    }

    fun disconnect() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        unregisterBond()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        cleanup()
        state.value = BleState.IDLE
    }

    private fun openGatt() {
        val addr = targetAddr ?: return
        if (adapter?.isEnabled != true) {
            state.value = BleState.OFF
            scheduleReconnect()
            return
        }
        state.value = if (attempt == 0) BleState.CONNECTING else BleState.RECONNECTING
        gatt?.close()
        val dev = adapter.getRemoteDevice(addr)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val delayS = BACKOFF_S[minOf(attempt, BACKOFF_S.size - 1)]
        attempt++
        state.value = BleState.RECONNECTING
        handler.postDelayed({ if (wanted && gatt == null) openGatt() }, delayS * 1000)
    }

    private fun cleanup() {
        ready = false
        writing = false
        rx = null
        queue.clear()
        pendingPose = null
        notifyQueue.clear()
        mtu = 23
    }

    private fun onLost() {
        unregisterBond()
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gatt = null
        cleanup()
        if (wanted) scheduleReconnect() else state.value = BleState.IDLE
    }

    // ---- bonding (the firmware shows a passkey on its TFT) -----------------------------------
    private val bondRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (dev?.address != targetAddr) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> { unregisterBond(); l.onLog("bonded"); requestMtuSoon() }
                BluetoothDevice.BOND_NONE -> {
                    unregisterBond()
                    l.onLog("pairing failed or cancelled")
                    wanted = false            // do not loop the pairing dialog
                    gatt?.disconnect()
                }
            }
        }
    }

    private fun startBonding(dev: BluetoothDevice) {
        state.value = BleState.BONDING
        if (!bondRegistered) {
            ContextCompat.registerReceiver(ctx, bondRx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            bondRegistered = true
        }
        l.onLog("bonding...")
        if (!dev.createBond()) l.onLog("createBond() returned false")
    }

    private fun unregisterBond() {
        if (bondRegistered) {
            try { ctx.unregisterReceiver(bondRx) } catch (_: Exception) {}
            bondRegistered = false
        }
    }

    private fun requestMtuSoon() {
        val g = gatt ?: return
        handler.postDelayed({ if (g === gatt) g.requestMtu(247) }, 300)
    }

    // ---- GATT callbacks (binder thread -> hop to the main handler) -------------------------------
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)   // ~11-15 ms
                    val dev = g.device
                    if (dev.bondState != BluetoothDevice.BOND_BONDED) startBonding(dev) else requestMtuSoon()
                } else {
                    l.onLog("disconnected (status $status)")
                    onLost()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                this@BleLink.mtu = mtu
                l.onLog("MTU $mtu")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val svc = g.getService(SERVICE)
                val r = svc?.getCharacteristic(RX)
                val t = svc?.getCharacteristic(TX)
                if (r == null || t == null) {
                    l.onLog("CubiNav service not found")
                    wanted = false
                    g.disconnect()
                    return@post
                }
                rx = r
                notifyQueue.add(t)
                g.getService(BATT_SVC)?.getCharacteristic(BATT_CHR)?.let { notifyQueue.add(it) }
                enableNextNotify(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            handler.post { if (g === gatt) enableNextNotify(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) l.onLog("write status $status")
                writeNext()
                onWriteIdle?.invoke()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { dispatch(c.uuid, value) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            val copy = v.copyOf()
            handler.post { dispatch(c.uuid, copy) }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNextNotify(g: BluetoothGatt) {
        val c = notifyQueue.poll()
        if (c == null) { finishReady(); return }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) { enableNextNotify(g); return }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
        if (!ok) enableNextNotify(g)
    }

    private fun finishReady() {
        ready = true
        attempt = 0
        state.value = BleState.CONNECTED
        l.onLog("link ready")
        l.onReady()
        writeNext()
    }

    private fun dispatch(uuid: UUID, v: ByteArray) {
        when (uuid) {
            TX -> l.onData(v)
            BATT_CHR -> if (v.isNotEmpty()) l.onBattery(v[0].toInt() and 0xFF)
        }
    }

    // ---- writes ---------------------------------------------------------------------------------
    /** Ordered command (route data, config, ...). */
    fun send(data: ByteArray) {
        if (!ready) return
        queue.add(data)
        writeNext()
    }

    /** POSE: only the newest one matters, so older pending poses are replaced. */
    fun sendPose(data: ByteArray) {
        if (!ready) return
        pendingPose = data
        writeNext()
    }

    @Suppress("DEPRECATION")
    private fun writeNext() {
        val g = gatt ?: return
        val c = rx ?: return
        if (!ready || writing) return
        val data = queue.pollFirst() ?: pendingPose?.also { pendingPose = null } ?: return
        writing = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = data
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            writing = false
            if ((data[0].toInt() and 0xFF) != Proto.POSE) queue.addFirst(data)
            handler.postDelayed({ writeNext() }, 30)
            return
        }
        val seq = ++writeSeq
        handler.postDelayed({ if (writing && seq == writeSeq) { writing = false; writeNext() } }, 400)
    }
}