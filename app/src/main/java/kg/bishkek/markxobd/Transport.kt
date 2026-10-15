package kg.bishkek.markxobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** Канал связи с адаптером: классический Bluetooth или BLE. */
interface Transport {
    val incoming: Channel<ByteArray>
    val kind: String
    suspend fun connect()
    suspend fun write(data: ByteArray)
    fun close()
}

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class ClassicTransport(private val device: BluetoothDevice) : Transport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val kind = "Bluetooth"
    private var socket: BluetoothSocket? = null
    @Volatile
    private var closed = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                socket = s
                startReader(s)
                return@withContext
            } catch (e: Exception) {
                last = e
                try {
                    s?.close()
                } catch (_: Exception) {
                }
            }
        }
        throw IOException("не удалось открыть Bluetooth-канал: ${last?.message}", last)
    }

    private fun startReader(s: BluetoothSocket) {
        Thread {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("адаптер закрыл соединение")
                    if (n > 0) incoming.trySend(buf.copyOf(n))
                }
            } catch (e: Exception) {
                incoming.close(if (closed) null else IOException("связь с адаптером потеряна", e))
            }
        }.apply {
            isDaemon = true
            name = "obd-reader"
        }.start()
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("нет соединения")
        s.outputStream.write(data)
        s.outputStream.flush()
    }

    override fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        incoming.close()
    }
}

@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : Transport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val kind = "BLE"
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val connected = CompletableDeferred<Unit>()
    private val servicesFound = CompletableDeferred<Boolean>()
    @Volatile
    private var writeAck: CompletableDeferred<Boolean>? = null
    @Volatile
    private var descAck: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val e = IOException("BLE-соединение разорвано (код $status)")
                connected.completeExceptionally(e)
                servicesFound.completeExceptionally(e)
                incoming.close(e)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesFound.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // Android 12 и ниже
            c.value?.let { incoming.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // Android 13+
            incoming.trySend(value.copyOf())
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun connect() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15_000) { connected.await() }
        val g = gatt ?: throw IOException("GATT недоступен")
        delay(300)
        g.discoverServices()
        val ok = withTimeout(15_000) { servicesFound.await() }
        if (!ok) throw IOException("не удалось получить сервисы BLE")

        val skip = setOf("1800", "1801", "180a", "180f")
        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        for (service in g.services) {
            val short = service.uuid.toString().substring(4, 8).lowercase()
            if (short in skip) continue
            val n = service.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val w = service.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (n != null && w != null) {
                notify = n
                write = w
                break
            }
        }
        if (notify == null || write == null) throw IOException("у устройства нет подходящего канала данных")
        writeChar = write

        g.setCharacteristicNotification(notify, true)
        val desc = notify.getDescriptor(CCCD_UUID)
        if (desc != null) {
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val ack = CompletableDeferred<Boolean>()
            descAck = ack
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, value)
            } else {
                desc.value = value
                g.writeDescriptor(desc)
            }
            withTimeoutOrNull(5_000) { ack.await() }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("нет соединения")
        val c = writeChar ?: throw IOException("нет канала записи")
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + 20, data.size))
            val ack = CompletableDeferred<Boolean>()
            writeAck = ack
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, type)
            } else {
                c.writeType = type
                c.value = chunk
                g.writeCharacteristic(c)
            }
            withTimeoutOrNull(2_000) { ack.await() }
            offset += chunk.size
        }
    }

    override fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        incoming.close()
    }
}
