package kg.bishkek.markxobd

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, WAITING }

/**
 * Вся работа с адаптером. Живёт на уровне процесса, а не экрана,
 * поэтому опрос и запись продолжаются при выключенном экране (вместе с ObdService).
 */
class ObdSession private constructor(private val app: Application) {

    companion object {
        @Volatile
        private var instance: ObdSession? = null

        fun get(app: Application): ObdSession =
            instance ?: synchronized(this) { instance ?: ObdSession(app).also { instance = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    val autoReconnect = MutableStateFlow(prefs.getBoolean("autoReconnect", true))
    val autoRecord = MutableStateFlow(prefs.getBoolean("autoRecord", true))
    val voiceOn = MutableStateFlow(prefs.getBoolean("voiceOn", true))
    val chargeLow = MutableStateFlow(false)
    val marks = MutableStateFlow<List<Mark>>(emptyList())
    val battery = MutableStateFlow<BatteryTest?>(null)
    val warmup = MutableStateFlow<WarmupState?>(null)
    val research = MutableStateFlow<List<String>>(emptyList())
    val researchBusy = MutableStateFlow(false)

    private var transport: Transport? = null
    @Volatile
    private var elm: Elm327? = null
    private var sessionJob: Job? = null
    @Volatile
    private var logger: CsvLogger? = null
    private val fanDetector = FanDetector()
    private val chargeMonitor = ChargeMonitor()
    // создаётся в главном потоке (ObdSession.get вызывается из интерфейса) — так надёжнее для TextToSpeech
    private val voice = Voice(app)
    private var lastAlarmAt = 0L
    private var warned100 = false
    private var engineOnSince: Long? = null
    private var engineOffSince: Long? = null
    @Volatile
    private var autoStartedRecording = false
    @Volatile
    private var pendingMark: String? = null
    /** Пользователь сам остановил запись — не перезапускать её до следующего запуска двигателя. */
    @Volatile
    private var autoSuppressed = false
    @Volatile
    private var pendingFanFlag = false
    @Volatile
    private var userDisconnect = false
    @Volatile
    private var pollPaused = false
    @Volatile
    private var warmupAnalyzer: WarmupAnalyzer? = null
    private var autoConnectTried = false
    @Volatile
    private var lastError: String? = null

    init {
        refreshLogs()
    }

    val lastDevice: DeviceItem?
        get() {
            val addr = prefs.getString("lastAddress", null) ?: return null
            val name = prefs.getString("lastName", addr) ?: addr
            return DeviceItem(name, addr, prefs.getBoolean("lastBle", false))
        }

    val sessionActive: Boolean get() = sessionJob?.isActive == true

    // ---------- Настройки ----------

    fun setAutoReconnect(v: Boolean) {
        autoReconnect.value = v
        prefs.edit().putBoolean("autoReconnect", v).apply()
    }

    fun setAutoRecord(v: Boolean) {
        autoRecord.value = v
        prefs.edit().putBoolean("autoRecord", v).apply()
    }

    fun setVoice(v: Boolean) {
        voiceOn.value = v
        prefs.edit().putBoolean("voiceOn", v).apply()
        if (v) voice.say("Голосовые предупреждения включены")
    }

    /** Один раз за запуск приложения: подключиться к последнему адаптеру, если включено автоподключение. */
    fun autoConnectOnStart() {
        if (autoConnectTried) return
        autoConnectTried = true
        val last = lastDevice ?: return
        if (autoReconnect.value && !sessionActive) connect(last)
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
            scope.launch {
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

    // ---------- Подключение и переподключение ----------

    fun connect(item: DeviceItem) {
        if (sessionActive) return
        stopBleScan()
        prefs.edit()
            .putString("lastAddress", item.address)
            .putString("lastName", item.name)
            .putBoolean("lastBle", item.ble)
            .apply()
        userDisconnect = false
        ObdService.start(app)
        sessionJob = scope.launch {
            var attempt = 0
            try {
                while (currentCoroutineContext().isActive) {
                    val wasConnected = runSession(item)
                    if (userDisconnect || !autoReconnect.value) break
                    attempt = if (wasConnected) 0 else attempt + 1
                    val wait = minOf(30, 5 + attempt * 5)
                    connState.value = ConnState.WAITING
                    for (s in wait downTo 1) {
                        val reason = lastError?.let { " ($it)" } ?: ""
                        status.value = if (wasConnected) {
                            "Связь потеряна$reason. Переподключение через $s с…"
                        } else {
                            "Не удалось подключиться$reason. Повтор через $s с… (попытка ${attempt + 1})"
                        }
                        delay(1000)
                    }
                }
            } finally {
                stopRecordingInternal()
                stopWarmupInternal()
                connState.value = ConnState.DISCONNECTED
                ObdService.stop(app)
            }
        }
    }

    /** Одна сессия связи. Возвращает true, если удалось полностью подключиться. */
    private suspend fun runSession(item: DeviceItem): Boolean {
        var connected = false
        lastError = null
        connState.value = ConnState.CONNECTING
        status.value = "Подключение к ${item.name}…"
        try {
            val dev = adapter?.getRemoteDevice(item.address) ?: throw IllegalStateException("Bluetooth недоступен")
            val t = openTransport(dev, item.ble)
            transport = t
            val e = Elm327(t) { addConsole(it) }
            status.value = "Инициализация адаптера…"
            e.initialize()
            status.value = "Определение датчиков…"
            val sup = e.supportedPids()
            val active = Pids.ALL.filter { it.pid in sup }
            supportedKeys.value = active.map { it.key }.toSet() + Pids.ADAPTER_V
            chargeMonitor.reset()
            chargeLow.value = false
            engineOnSince = null
            engineOffSince = null
            elm = e
            connected = true
            connState.value = ConnState.CONNECTED
            status.value = "Подключено: ${item.name} · ${e.protocolName} · ${e.version}"
            pollLoop(e, active)
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            if (!userDisconnect) status.value = "Ошибка: $lastError"
            addConsole("! ${ex.message ?: ex}")
        } finally {
            elm = null
            pollPaused = false
            try {
                transport?.close()
            } catch (_: Exception) {
            }
            transport = null
            overheat.value = false
            chargeLow.value = false
            connState.value = ConnState.DISCONNECTED
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    private suspend fun openTransport(dev: BluetoothDevice, ble: Boolean): Transport {
        if (ble) {
            val b = BleTransport(app, dev)
            try {
                b.connect()
                return b
            } catch (e: Exception) {
                b.close()
                throw e
            }
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
                val b = BleTransport(app, dev)
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

    // ---------- Опрос ----------

    private suspend fun pollLoop(e: Elm327, active: List<PidDef>) {
        var cycle = 0L
        while (currentCoroutineContext().isActive) {
            if (pollPaused) {
                delay(200)
                continue
            }
            val map = HashMap(values.value)
            for (p in active) {
                if (pollPaused) break
                if (!p.priority && cycle % 3L != 0L) continue
                val v = try {
                    e.readPid(p)
                } catch (_: ElmTimeoutException) {
                    null
                }
                if (v != null) {
                    map[p.key] = v
                    history.add(p.key, System.currentTimeMillis(), v)
                }
            }
            if (cycle % 5L == 0L && !pollPaused) {
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
            val mark = pendingMark
            pendingMark = null
            logger?.write(now, map, pendingFanFlag, mark)
            pendingFanFlag = false
            historyTick.value = now
            cycle++
        }
    }

    private fun analyze(now: Long, map: Map<String, Double>) {
        val coolant = map["coolant"]
        val rpm = map["rpm"]
        val v = map["ecuV"] ?: map[Pids.ADAPTER_V]

        // ---- Температура ----
        if (coolant != null) {
            val hot = coolant >= Pids.OVERHEAT_C
            overheat.value = hot
            if (hot && now - lastAlarmAt > 30_000) {
                lastAlarmAt = now
                alert("Перегрев! Температура ${coolant.toInt()} градусов. Остановитесь и заглушите двигатель.", urgent = true)
            }
            if (!warned100 && coolant >= 100 && !hot) {
                warned100 = true
                alert("Температура ${coolant.toInt()} градусов.", urgent = false)
            }
            if (coolant < 97) warned100 = false
        }

        // ---- Вентиляторы ----
        if (fanDetector.feed(now, v, rpm, coolant)) {
            fanEvents.value = (listOf(FanEvent(now, coolant, fanDetector.lastDrop)) + fanEvents.value).take(50)
            pendingFanFlag = true
            warmupAnalyzer?.onFan(coolant)
        }

        // ---- Зарядка ----
        if (chargeMonitor.feed(now, v, rpm)) {
            val vs = v?.let { String.format(Locale("ru"), "%.1f", it) } ?: "?"
            alert("Внимание. Генератор не заряжает, напряжение $vs вольт.", urgent = false)
            addMarkInternal(now, "Генератор не заряжает ($vs В)", coolant)
        }
        chargeLow.value = chargeMonitor.isLow

        // ---- Автозапись по двигателю ----
        val running = rpm != null && rpm > 400
        if (running) {
            engineOffSince = null
            val since = engineOnSince ?: now.also { engineOnSince = it }
            if (autoRecord.value && !autoSuppressed && logger == null && now - since >= 3_000) {
                startRecording()
                autoStartedRecording = true
            }
        } else if (rpm != null) {
            engineOnSince = null
            val since = engineOffSince ?: now.also { engineOffSince = it }
            if (now - since >= 15_000) autoSuppressed = false
            if (autoStartedRecording && logger != null && now - since >= 15_000) {
                stopRecordingInternal()
            }
        }

        warmupAnalyzer?.let { wa ->
            val st = wa.update(now, coolant, rpm)
            warmup.value = st
            if (!st.running) warmupAnalyzer = null
        }
    }

    private fun alert(text: String, urgent: Boolean) {
        if (voiceOn.value) voice.say(text, urgent) else if (urgent) voice.beep(true)
    }

    // ---------- Метки ----------

    fun addMark(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        addMarkInternal(System.currentTimeMillis(), text, values.value["coolant"])
    }

    private fun addMarkInternal(now: Long, label: String, coolant: Double?) {
        marks.value = (listOf(Mark(now, label, coolant)) + marks.value).take(100)
        pendingMark = pendingMark?.let { "$it / $label" } ?: label
        // без записи метка останется только в списке на экране — запись не запущена
    }

    // ---------- Тест аккумулятора ----------

    fun startBatteryTest() {
        val e = elm
        if (e == null || connState.value != ConnState.CONNECTED) {
            battery.value = BatteryTest(false, "Сначала подключитесь к адаптеру")
            return
        }
        if (battery.value?.running == true) return
        val rpm = values.value["rpm"]
        if (rpm != null && rpm > 300) {
            battery.value = BatteryTest(false, "Заглушите двигатель, оставьте зажигание включённым и запустите тест снова")
            return
        }
        scope.launch {
            pollPaused = true
            delay(300) // даём завершиться текущему запросу опроса
            val samples = ArrayList<Pair<Long, Float>>()
            var rest: Double? = null
            var min: Double? = null
            var charging: Double? = null
            var lostLink = false
            try {
                battery.value = BatteryTest(true, "Измеряю напряжение покоя…")
                val restVals = ArrayList<Double>()
                repeat(6) {
                    val v = try {
                        e.adapterVoltage()
                    } catch (_: ElmTimeoutException) {
                        null
                    }
                    if (v != null) restVals.add(v)
                    delay(150)
                }
                rest = restVals.takeIf { it.isNotEmpty() }?.average()
                val start = System.currentTimeMillis()
                var startedAt: Long? = null
                val chargingVals = ArrayList<Double>()
                battery.value = BatteryTest(true, "Заводите двигатель (жду до 30 с)…", rest)
                while (System.currentTimeMillis() - start < 30_000) {
                    val v = try {
                        e.adapterVoltage()
                    } catch (_: ElmTimeoutException) {
                        null
                    } ?: continue
                    val now = System.currentTimeMillis()
                    samples.add(now to v.toFloat())
                    min = minOf(min ?: v, v)
                    val r = rest ?: v
                    if (startedAt == null && (min ?: v) < r - 0.8 && v > 13.2) startedAt = now
                    if (startedAt != null) {
                        if (now - startedAt > 1500) chargingVals.add(v)
                        if (now - startedAt > 5000) break
                    }
                    battery.value = BatteryTest(
                        true,
                        if (startedAt == null) "Заводите двигатель…" else "Двигатель запущен, проверяю зарядку…",
                        rest, min, null, samples.toList(),
                    )
                }
                charging = chargingVals.takeIf { it.isNotEmpty() }?.average()
                if (startedAt == null && min != null && rest != null && min!! > rest!! - 0.5) {
                    battery.value = BatteryTest(
                        false,
                        "Запуск не обнаружен за 30 с. Запустите тест и сразу заводите двигатель.",
                        rest, min, null, samples.toList(),
                    )
                    return@launch
                }
                try {
                    e.lightReinit()
                } catch (_: Exception) {
                }
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                lostLink = true
                addConsole("! тест аккумулятора: ${ex.message}")
            } finally {
                pollPaused = false
            }
            battery.value = BatteryTest(
                false, "Готово", rest, min, charging, samples.toList(),
                BatteryVerdict.make(rest, min, charging, lostLink),
            )
        }
    }

    // ---------- Тест прогрева ----------

    fun startWarmupTest() {
        if (connState.value != ConnState.CONNECTED) {
            warmup.value = null
            status.value = "Сначала подключитесь к адаптеру"
            return
        }
        val now = System.currentTimeMillis()
        val coolant = values.value["coolant"]
        val wa = WarmupAnalyzer(now, coolant)
        warmupAnalyzer = wa
        warmup.value = wa.update(now, coolant, values.value["rpm"])
        if (logger == null) startRecording()
    }

    fun stopWarmupTest() = stopWarmupInternal()

    private fun stopWarmupInternal() {
        val wa = warmupAnalyzer ?: return
        warmupAnalyzer = null
        warmup.value = wa.stopByUser(System.currentTimeMillis(), values.value["coolant"])
    }

    // ---------- Исследование расширенных данных ----------

    fun sendRaw(cmd: String) {
        val c = cmd.trim().uppercase(Locale.US)
        if (c.isEmpty()) return
        if (Research.isBlocked(c)) {
            addResearch("× $c — команда заблокирована: она может изменить данные в блоке")
            return
        }
        researchOp { e ->
            addResearch("> $c")
            val r = try {
                e.send(c, 4000)
            } catch (_: ElmTimeoutException) {
                "(нет ответа)"
            }
            addResearch(r.ifBlank { "(пусто)" })
        }
    }

    fun scanToyota() = researchOp { e ->
        addResearch("=== Сканирование расширенных данных ===")
        fun ok(r: String) = r.isNotBlank() && !r.contains("NO DATA") && !r.contains("NODATA") &&
            !r.contains("?") && !r.contains("ERROR")

        suspend fun q(cmd: String, timeout: Long = 2000): String? = try {
            e.send(cmd, timeout)
        } catch (_: ElmTimeoutException) {
            null
        }

        // Информация о блоке (Mode 09)
        for (cmd in listOf("0902", "0904", "090A")) {
            val r = q(cmd, 4000) ?: continue
            if (ok(r)) addResearch("> $cmd\n$r\n  текст: ${asciiOf(r)}")
        }
        // Результаты тестов бортовой диагностики (Mode 06)
        q("0600")?.let { if (ok(it)) addResearch("> 0600\n$it") }

        // Toyota: локальные идентификаторы Mode 21 у блока двигателя
        q("ATH1")
        q("ATSH7E0")
        var found = 0
        for (id in 0x01..0x3F) {
            val cmd = String.format(Locale.US, "21%02X", id)
            val r = q(cmd, 1500) ?: continue
            if (ok(r) && !r.replace(" ", "").contains("7F21")) {
                found++
                addResearch("> $cmd\n$r")
            }
        }
        // Пара известных идентификаторов Mode 22
        for (cmd in listOf("22F190", "22F18C", "220101")) {
            val r = q(cmd, 1500) ?: continue
            if (ok(r) && !r.replace(" ", "").contains("7F22")) addResearch("> $cmd\n$r")
        }
        q("ATSH7DF")
        q("ATH0")
        addResearch("=== Готово. Ответили идентификаторов Mode 21: $found ===")
        saveResearch()
    }

    private fun asciiOf(r: String): String {
        val sb = StringBuilder()
        for (line in r.lines()) {
            val data = line.substringAfter(':').replace(" ", "")
            Pids.hexBytes(data).forEach { b -> if (b in 0x20..0x7E) sb.append(b.toChar()) }
        }
        return sb.toString()
    }

    private fun researchOp(block: suspend (Elm327) -> Unit) {
        val e = elm
        if (e == null || connState.value != ConnState.CONNECTED) {
            addResearch("Сначала подключитесь к адаптеру")
            return
        }
        if (researchBusy.value) return
        scope.launch {
            researchBusy.value = true
            pollPaused = true
            delay(300)
            try {
                block(e)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                addResearch("! ${ex.message}")
            } finally {
                pollPaused = false
                researchBusy.value = false
            }
        }
    }

    private fun addResearch(line: String) {
        research.value = (research.value + line).takeLast(400)
    }

    /** Сохраняет журнал исследования в файл рядом с записями поездок. */
    fun saveResearch(): File? {
        val lines = research.value
        if (lines.isEmpty()) return null
        val dir = logsDir().also { it.mkdirs() }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val f = File(dir, "research_$name.txt")
        f.writeText(lines.joinToString("\n"))
        refreshLogs()
        return f
    }

    fun clearResearch() {
        research.value = emptyList()
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

    private fun logsDir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")

    fun toggleRecording() {
        if (logger != null) {
            autoSuppressed = true
            stopRecordingInternal()
        } else {
            startRecording()
        }
    }

    /** PDF-отчёт для мастера по записи поездки. */
    fun buildReport(f: File): File? {
        val d = TripData.load(f) ?: return null
        return try {
            PdfReport.build(logsDir(), d, dtcResult.value).also { refreshLogs() }
        } catch (_: Exception) {
            null
        }
    }

    private fun startRecording() {
        if (logger != null) return
        autoStartedRecording = false
        chargeMonitor.reset()
        val keys = Pids.DISPLAY.map { it.key }
        val l = CsvLogger(logsDir(), keys)
        logger = l
        recordingFile.value = l.file
    }

    private fun stopRecordingInternal() {
        val l = logger ?: return
        logger = null
        autoStartedRecording = false
        l.close()
        recordingFile.value = null
        refreshLogs()
    }

    fun refreshLogs() {
        logFiles.value = logsDir().listFiles { f -> f.name.endsWith(".csv") || f.name.endsWith(".txt") || f.name.endsWith(".pdf") }
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
        val stored = e.readStoredDtcs()
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
        scope.launch {
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
}
