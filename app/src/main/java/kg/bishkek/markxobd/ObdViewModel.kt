package kg.bishkek.markxobd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

class ObdViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val btManager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = btManager?.adapter

    val connState = MutableStateFlow(ConnState.DISCONNECTED)
    val status = MutableStateFlow("Не подключено")
    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val supportedKeys = MutableStateFlow<Set<String>>(emptySet())
    val history = History(maxAgeMs = 30 * 60 * 1000L)
    val historyTick = MutableStateFlow(0L)
    val chartKeys = MutableStateFlow(loadChartKeys())
    val fanEvents = MutableStateFlow<List<FanEvent>>(emptyList())
    val overheat = MutableStateFlow(false)
    val console = MutableStateFlow<List<String>>(emptyList())
    val recordingFile = MutableStateFlow<File?>(null)
    val dtcResult = MutableStateFlow<DtcResult?>(null)
    val freezeFrame = MutableStateFlow<FreezeFrame?>(null)
    val dtcBusy = MutableStateFlow(false)
    val dtcMessage = MutableStateFlow<String?>(null)
    val pairedDevices = MutableStateFlow<List<DeviceItem>>(emptyList())
    val bleDevices = MutableStateFlow<List<DeviceItem>>(emptyList())
    val scanning = MutableStateFlow(false)
    val logFiles = MutableStateFlow<List<File>>(emptyList())

    private var transport: Transport? = null
    private var elm: Elm327? = null
    private var sessionJob: Job? = null
    @Volatile
    private var logger: CsvLogger? = null
    private var voltageEma: Double? = null
    private var lastFanEventAt = 0L
    private var lastAlarmAt = 0L
    @Volatile
    private var pendingFanFlag = false
    private var userDisconnect = false

    init {
        refreshLogs()
    }

    val lastDevice: DeviceItem?
        get() {
            val addr = prefs.getString("lastAddress", null) ?: return null
            val name = prefs.getString("lastName", addr) ?: addr
            return DeviceItem(name, addr, prefs.getBoolean("lastBle", false))
        }

    // ---------- Bluetooth ----------

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        try {
            pairedDevices.value = adapter?.bondedDevices
                ?.map { DeviceItem(it.name ?: it.address, it.address, false) }
                ?.sortedByDescending { it.name.contains("link", true) || it.name.contains("obd", true) }
                ?: emptyList()
        } catch (e: SecurityException) {
            status.value = "Нет разрешения на Bluetooth"
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = try {
                d.name
            } catch (_: SecurityException) {
                null
            } ?: result.scanRecord?.deviceName ?: return
            if (bleDevices.value.none { it.address == d.address }) {
                bleDevices.value = bleDevices.value + DeviceItem(name, d.address, true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            status.value = "BLE недоступен (Bluetooth выключен?)"
            return
        }
        if (scanning.value) return
        bleDevices.value = emptyList()
        try {
            scanner.startScan(scanCallback)
            scanning.value = true
            viewModelScope.launch {
                delay(10_000)
                stopBleScan()
            }
        } catch (e: SecurityException) {
            status.value = "Нет разрешения на поиск Bluetooth"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning.value = false
    }

    // ---------- Подключение ----------

    fun connect(item: DeviceItem) {
        if (connState.value != ConnState.DISCONNECTED) return
        stopBleScan()
        val dev = try {
            adapter?.getRemoteDevice(item.address)
        } catch (_: Exception) {
            null
        } ?: run {
            status.value = "Bluetooth недоступен"
            return
        }
        prefs.edit()
            .putString("lastAddress", item.address)
            .putString("lastName", item.name)
            .putBoolean("lastBle", item.ble)
            .apply()
        userDisconnect = false
        connState.value = ConnState.CONNECTING
        status.value = "Подключение к ${item.name}…"
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t = openTransport(dev, item.ble)
                transport = t
                val e = Elm327(t) { addConsole(it) }
                elm = e
                status.value = "Инициализация адаптера…"
                e.initialize()
                status.value = "Определение датчиков…"
                val sup = e.supportedPids()
                val active = Pids.ALL.filter { it.pid in sup }
                supportedKeys.value = active.map { it.key }.toSet() + Pids.ADAPTER_V
                voltageEma = null
                connState.value = ConnState.CONNECTED
                status.value = "Подключено: ${item.name} · ${t.kind} · ${e.version}"
                pollLoop(e, active)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                if (!userDisconnect) {
                    status.value = "Ошибка: ${ex.message ?: ex.javaClass.simpleName}"
                }
                addConsole("! ${ex.message ?: ex}")
            } finally {
                cleanup()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openTransport(dev: BluetoothDevice, ble: Boolean): Transport {
        if (ble) {
            return BleTransport(getApplication(), dev).also { it.connect() }
        }
        val classic = ClassicTransport(dev)
        try {
            classic.connect()
            return classic
        } catch (e: Exception) {
            classic.close()
            val type = try {
                dev.type
            } catch (_: SecurityException) {
                BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
            if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                addConsole("Классический Bluetooth не удался, пробую BLE…")
                val b = BleTransport(getApplication(), dev)
                try {
                    b.connect()
                    return b
                } catch (be: Exception) {
                    b.close()
                    throw be
                }
            }
            throw e
        }
    }

    fun disconnect() {
        userDisconnect = true
        status.value = "Отключено"
        sessionJob?.cancel()
    }

    private fun cleanup() {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        elm = null
        overheat.value = false
        stopRecordingInternal()
        connState.value = ConnState.DISCONNECTED
    }

    // ---------- Опрос ----------

    private suspend fun pollLoop(e: Elm327, active: List<PidDef>) {
        var cycle = 0L
        while (currentCoroutineContext().isActive) {
            val map = HashMap(values.value)
            for (p in active) {
                if (!p.priority && cycle % 3L != 0L) continue
                val v = try {
                    e.readPid(p)
                } catch (_: ElmTimeoutException) {
                    null
                }
                if (v != null) {
                    val now = System.currentTimeMillis()
                    map[p.key] = v
                    history.add(p.key, now, v)
                }
            }
            if (cycle % 5L == 0L) {
                val av = try {
                    e.adapterVoltage()
                } catch (_: ElmTimeoutException) {
                    null
                }
                if (av != null) {
                    map[Pids.ADAPTER_V] = av
                    history.add(Pids.ADAPTER_V, System.currentTimeMillis(), av)
                }
            }
            val now = System.currentTimeMillis()
            values.value = map
            analyze(now, map)
            logger?.write(now, map, pendingFanFlag)
            pendingFanFlag = false
            historyTick.value = now
            cycle++
        }
    }

    private fun analyze(now: Long, map: Map<String, Double>) {
        val coolant = map["coolant"]
        if (coolant != null) {
            val hot = coolant >= Pids.OVERHEAT_C
            overheat.value = hot
            if (hot && now - lastAlarmAt > 20_000) {
                lastAlarmAt = now
                beep()
            }
        }
        // Вентиляторы — большая нагрузка: в момент включения напряжение на короткое время проседает.
        val v = map["ecuV"] ?: map[Pids.ADAPTER_V]
        val rpm = map["rpm"] ?: 0.0
        if (v != null && rpm > 400) {
            val ema = voltageEma
            if (ema != null && ema - v >= 0.2 && now - lastFanEventAt > 30_000) {
                lastFanEventAt = now
                fanEvents.value = (listOf(FanEvent(now, coolant, ema - v)) + fanEvents.value).take(50)
                pendingFanFlag = true
            }
            voltageEma = if (ema == null) v else ema * 0.85 + v * 0.15
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
            viewModelScope.launch {
                delay(2000)
                tg.release()
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Графики ----------

    fun toggleChart(key: String) {
        val cur = chartKeys.value
        chartKeys.value = if (key in cur) cur - key else cur + key
        prefs.edit().putString("chartKeys", chartKeys.value.joinToString(",")).apply()
    }

    private fun loadChartKeys(): List<String> =
        prefs.getString("chartKeys", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: listOf("coolant", "ecuV")

    fun clearHistory() {
        history.clear()
        fanEvents.value = emptyList()
        historyTick.value = System.currentTimeMillis()
    }

    // ---------- Запись поездки ----------

    private fun logsDir(): File =
        File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "logs")

    fun toggleRecording() {
        if (logger != null) {
            stopRecordingInternal()
        } else {
            val keys = Pids.DISPLAY.map { it.key }
            val l = CsvLogger(logsDir(), keys)
            logger = l
            recordingFile.value = l.file
        }
    }

    private fun stopRecordingInternal() {
        val l = logger ?: return
        logger = null
        l.close()
        recordingFile.value = null
        refreshLogs()
    }

    fun refreshLogs() {
        logFiles.value = logsDir().listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteLog(f: File) {
        if (f == recordingFile.value) return
        f.delete()
        refreshLogs()
    }

    // ---------- Ошибки ----------

    fun readDtc() = dtcOp { e ->
        val stored = e.readDtcs(0x03)
        val pending = e.readDtcs(0x07)
        val permanent = try {
            e.readDtcs(0x0A)
        } catch (_: Exception) {
            emptyList()
        }
        dtcResult.value = DtcResult(stored, pending, permanent, System.currentTimeMillis())
        dtcMessage.value = if (stored.isEmpty() && pending.isEmpty() && permanent.isEmpty()) "Ошибок нет" else null
    }

    fun readFreeze() = dtcOp { e ->
        val ff = e.readFreezeFrame()
        freezeFrame.value = ff
        if (ff.dtc == null) dtcMessage.value = "Стоп-кадра нет"
    }

    fun clearDtc() = dtcOp { e ->
        val ok = e.clearDtcs()
        dtcResult.value = null
        freezeFrame.value = null
        dtcMessage.value = if (ok) "Ошибки стёрты" else "Блок не подтвердил стирание"
    }

    private fun dtcOp(block: suspend (Elm327) -> Unit) {
        val e = elm
        if (e == null || connState.value != ConnState.CONNECTED) {
            dtcMessage.value = "Сначала подключитесь к адаптеру"
            return
        }
        if (dtcBusy.value) return
        dtcMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            dtcBusy.value = true
            try {
                block(e)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                dtcMessage.value = "Ошибка: ${ex.message}"
            } finally {
                dtcBusy.value = false
            }
        }
    }

    // ---------- Консоль ----------

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun addConsole(line: String) {
        val stamped = "${timeFmt.format(Date())} $line"
        console.value = (console.value + stamped).takeLast(200)
    }

    override fun onCleared() {
        sessionJob?.cancel()
        stopBleScan()
        cleanup()
        super.onCleared()
    }
}
