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

    // ---- новые возможности ----
    val thresholds = MutableStateFlow(loadThresholds())
    val liveTrip = MutableStateFlow<LiveTrip?>(null)
    val accel = MutableStateFlow<AccelState?>(null)
    val accelHistory = MutableStateFlow(loadAccelHistory())
    val misfires = MutableStateFlow<MisfireResult?>(null)
    val misfireBusy = MutableStateFlow(false)
    val newDtc = MutableStateFlow<List<String>>(emptyList())
    val driveNight = MutableStateFlow(prefs.getBoolean("driveNight", true))
    val gpsOn = MutableStateFlow(prefs.getBoolean("gpsOn", false))
    val gps = GpsTracker(app)
    val fuelTank = MutableStateFlow(FuelTank.decode(prefs.getString("fuelTank", null)))
    private var fuelSavedAt = 0L
    private var lowFuelWarned = false
    @Volatile
    private var accelRun: AccelRun? = null
    private var lastRpmAlertAt = 0L
    private var lastPollAt = 0L
    @Volatile
    private var tcmOk = false

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

    fun setThresholds(t: ThresholdValues) {
        val fixed = t.copy(
            overheat = t.overheat.coerceIn(90.0, 130.0),
            warn = t.warn.coerceIn(80.0, t.overheat.coerceIn(90.0, 130.0)),
            lowV = t.lowV.coerceIn(11.0, 14.5),
            rpmMax = if (t.rpmMax <= 0) 0.0 else t.rpmMax.coerceIn(1500.0, 8000.0),
            fuelPrice = t.fuelPrice.coerceAtLeast(0.0),
        )
        fixed.apply()
        thresholds.value = fixed
        prefs.edit()
            .putFloat("thOverheat", fixed.overheat.toFloat())
            .putFloat("thWarn", fixed.warn.toFloat())
            .putFloat("thLowV", fixed.lowV.toFloat())
            .putFloat("thRpm", fixed.rpmMax.toFloat())
            .putFloat("fuelPrice", fixed.fuelPrice.toFloat())
            .apply()
    }

    private fun loadThresholds(): ThresholdValues {
        val d = ThresholdValues()
        val t = ThresholdValues(
            overheat = prefs.getFloat("thOverheat", d.overheat.toFloat()).toDouble(),
            warn = prefs.getFloat("thWarn", d.warn.toFloat()).toDouble(),
            lowV = prefs.getFloat("thLowV", d.lowV.toFloat()).toDouble(),
            rpmMax = prefs.getFloat("thRpm", d.rpmMax.toFloat()).toDouble(),
            fuelPrice = prefs.getFloat("fuelPrice", d.fuelPrice.toFloat()).toDouble(),
        )
        t.apply()
        return t
    }

    fun setDriveNight(v: Boolean) {
        driveNight.value = v
        prefs.edit().putBoolean("driveNight", v).apply()
    }

    fun setGps(v: Boolean) {
        gpsOn.value = v
        prefs.edit().putBoolean("gpsOn", v).apply()
        if (!v) gps.stop() else if (logger != null) gps.start()
    }

    fun refuel(liters: Double, full: Boolean) {
        val t = fuelTank.value.refuel(liters, full, System.currentTimeMillis())
        fuelTank.value = t
        lowFuelWarned = false
        saveFuel()
    }

    fun setTankSize(l: Double) {
        fuelTank.value = fuelTank.value.copy(tankL = l.coerceIn(30.0, 120.0))
        saveFuel()
    }

    private fun saveFuel() {
        fuelSavedAt = System.currentTimeMillis()
        prefs.edit().putString("fuelTank", fuelTank.value.encode()).apply()
    }

    fun dismissNewDtc() {
        newDtc.value = emptyList()
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
            val keys = active.map { it.key }.toMutableSet()
            keys.add(Pids.ADAPTER_V)
            if ("maf" in keys || ("load" in keys && "rpm" in keys)) {
                keys.add(Pids.FUEL_LH)
                if ("speed" in keys) keys.add(Pids.FUEL_L100)
            }
            supportedKeys.value = keys
            status.value = "Проверка ошибок…"
            checkNewDtcs(e)
            status.value = "Проверка коробки передач…"
            tcmOk = try {
                pollTcm(e, HashMap(), System.currentTimeMillis())
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                false
            }
            if (tcmOk) {
                keys.add(Pids.ATF_A)
                supportedKeys.value = keys.toSet()
            }
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
            saveFuel()
            lastPollAt = 0L
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
            // во время замера разгона опрашиваем только скорость — так точнее время
            val fast = accelRun?.active == true
            for (p in active) {
                if (pollPaused) break
                if (fast && p.key != "speed" && !(p.key == "coolant" && cycle % 10L == 0L)) continue
                if (!fast && !p.priority && cycle % 3L != 0L) continue
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
            if (!fast && cycle % 5L == 0L && !pollPaused) {
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
            if (tcmOk && !fast && !pollPaused && cycle % 10L == 5L) {
                try {
                    pollTcm(e, map, System.currentTimeMillis())
                } catch (_: ElmTimeoutException) {
                }
            }
            val now = System.currentTimeMillis()
            updateDerived(now, map)
            values.value = map
            analyze(now, map)
            val mark = pendingMark
            pendingMark = null
            logger?.let { l ->
                val g = gps.freshValues(now)
                l.write(now, if (g.isEmpty()) map else map + g, pendingFanFlag, mark)
            }
            pendingFanFlag = false
            historyTick.value = now
            cycle++
        }
    }

    /** Опрос АКПП: два кандидата на температуру масла и сырые байты для расшифровки. */
    private suspend fun pollTcm(e: Elm327, map: HashMap<String, Double>, now: Long): Boolean {
        val r = e.queryModule("7E1", listOf("21B4", "21B6", "21D9", "21E2"))
        if (r.isEmpty()) return false
        fun raw(prefix: String, bytes: List<Int>?, n: Int) {
            for (i in 0 until n) {
                val b = bytes?.getOrNull(i) ?: continue
                map["${prefix}_$i"] = b.toDouble()
            }
        }
        raw("tB4", r["21B4"], 6)
        raw("tB6", r["21B6"], 9)
        raw("tD9", r["21D9"], 8)
        raw("tE2", r["21E2"], 5)
        // 21B4[2] — копия температуры воздуха, 21B4[3] и 21B6[4] — копии температуры ОЖ.
        // 21D9[4] растёт медленнее ОЖ и не повторяет её — главный кандидат на температуру масла.
        r["21D9"]?.getOrNull(4)?.let {
            map[Pids.ATF_A] = it - 40.0
            history.add(Pids.ATF_A, now, it - 40.0)
        }
        return true
    }

    /** Расход топлива по MAF и счётчики текущей поездки. */
    private fun updateDerived(now: Long, map: HashMap<String, Double>) {
        val dt = if (lastPollAt == 0L) 0.0 else ((now - lastPollAt) / 1000.0).coerceIn(0.0, 10.0)
        lastPollAt = now
        val rpm = map["rpm"]
        val speed = map["speed"]
        val maf = Fuel.airflow(map["maf"], map["load"], rpm)
        val lh = if (maf != null && (rpm ?: 0.0) > 300) Fuel.litersPerHour(maf) else if (rpm != null && rpm < 300) 0.0 else null
        if (lh != null) {
            map[Pids.FUEL_LH] = lh
            history.add(Pids.FUEL_LH, now, lh)
            if (speed != null && speed > 3) {
                val l100 = lh / speed * 100.0
                map[Pids.FUEL_L100] = l100
            } else {
                map.remove(Pids.FUEL_L100)
            }
        }
        if (dt > 0) {
            val km = (speed ?: 0.0) * dt / 3600.0
            val tank = fuelTank.value.consume((lh ?: 0.0) * dt / 3600.0, km)
            fuelTank.value = tank
            if (now - fuelSavedAt > 30_000) saveFuel()
            if (tank.known && tank.levelL < 8 && !lowFuelWarned) {
                lowFuelWarned = true
                alert("Топлива осталось примерно ${tank.levelL.toInt()} литров", urgent = false)
            }
        }
        val trip = liveTrip.value
        if (trip != null && dt > 0) {
            liveTrip.value = trip.copy(
                distKm = trip.distKm + (speed ?: 0.0) * dt / 3600.0,
                fuelL = trip.fuelL + (lh ?: 0.0) * dt / 3600.0,
            )
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
            if (!warned100 && coolant >= Thresholds.warn && !hot) {
                warned100 = true
                alert("Температура ${coolant.toInt()} градусов.", urgent = false)
            }
            if (coolant < Thresholds.warn - 3) warned100 = false
        }

        // ---- Обороты ----
        val rpmMax = Thresholds.rpmMax
        if (rpmMax > 0 && rpm != null && rpm >= rpmMax && now - lastRpmAlertAt > 20_000) {
            lastRpmAlertAt = now
            alert("Обороты ${(rpm / 100).toInt() * 100}", urgent = false)
        }

        // ---- Замер разгона ----
        accelRun?.let { run ->
            val st = run.feed(now, map["speed"])
            accel.value = st
            if (st.phase == AccelPhase.DONE) {
                accelRun = null
                run.result(now)?.let { saveAccel(it) }
                val words = listOfNotNull(
                    st.t60?.let { "до шестидесяти ${speak(it)}" },
                    st.t100?.let { "до ста ${speak(it)}" },
                )
                if (words.isNotEmpty()) alert("Разгон: ${words.joinToString(", ")}", urgent = false)
            }
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
            val st = wa.update(now, coolant, rpm, map["speed"])
            warmup.value = st
            if (!st.running) warmupAnalyzer = null
        }
    }

    /** Сравнивает коды с прошлым подключением и сообщает о новых. */
    private suspend fun checkNewDtcs(e: Elm327) {
        val stored = try { e.readStoredDtcs() } catch (_: ElmTimeoutException) { return }
        val pending = try { e.readDtcs(0x07) } catch (_: ElmTimeoutException) { emptyList() }
        val now = System.currentTimeMillis()
        dtcResult.value = DtcResult(stored, pending, emptyList(), now)
        val current = (stored + pending).toSet()
        val known = prefs.getString("knownDtcs", null)?.split(",")?.filter { it.isNotBlank() }?.toSet()
        prefs.edit().putString("knownDtcs", current.joinToString(",")).apply()
        // при самом первом подключении сравнивать не с чем
        if (known == null) return
        val fresh = (current - known).sorted()
        if (fresh.isEmpty()) return
        newDtc.value = fresh
        addMarkInternal(now, "Новая ошибка: ${fresh.joinToString(" ")}", values.value["coolant"])
        val spoken = fresh.joinToString(", ") { it.toCharArray().joinToString(" ") }
        alert(if (fresh.size == 1) "Новая ошибка двигателя: $spoken" else "Новые ошибки двигателя: $spoken", urgent = false)
    }

    private fun rememberDtcs(r: DtcResult) {
        prefs.edit().putString("knownDtcs", (r.stored + r.pending).toSet().joinToString(",")).apply()
        newDtc.value = emptyList()
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

    // ---------- Разгон ----------

    fun startAccel() {
        if (connState.value != ConnState.CONNECTED) {
            accel.value = AccelState(AccelPhase.DONE, "Сначала подключитесь к адаптеру")
            return
        }
        if ("speed" !in supportedKeys.value) {
            accel.value = AccelState(AccelPhase.DONE, "Блок не отдаёт скорость")
            return
        }
        val run = AccelRun()
        accelRun = run
        accel.value = run.feed(System.currentTimeMillis(), values.value["speed"])
    }

    fun cancelAccel() {
        accelRun = null
        accel.value = null
    }

    private fun speak(sec: Double): String = String.format(Locale("ru"), "%.1f", sec).replace(',', ' ') + " секунды"

    private fun saveAccel(r: AccelResult) {
        val list = (listOf(r) + accelHistory.value).take(20)
        accelHistory.value = list
        prefs.edit().putString("accelLog", list.joinToString(";") { it.encode() }).apply()
    }

    private fun loadAccelHistory(): List<AccelResult> =
        prefs.getString("accelLog", null)?.split(";")?.mapNotNull { AccelResult.decode(it) } ?: emptyList()

    fun clearAccelHistory() {
        accelHistory.value = emptyList()
        prefs.edit().remove("accelLog").apply()
    }

    // ---------- Пропуски зажигания ----------

    fun readMisfires() {
        val e = elm
        if (e == null || connState.value != ConnState.CONNECTED) {
            misfires.value = MisfireResult(null, "Сначала подключитесь к адаптеру", System.currentTimeMillis())
            return
        }
        if (misfireBusy.value) return
        scope.launch {
            misfireBusy.value = true
            pollPaused = true
            delay(300)
            try {
                val counts = e.readMisfires()
                val msg = when {
                    counts == null -> "Блок не отдаёт счётчики пропусков по цилиндрам (Mode 06). " +
                        "На японских Toyota это обычно так. Следите за кодами P0300–P0306 на вкладке «Ошибки»."
                    counts.values.all { it == 0 } -> "Пропусков нет"
                    else -> {
                        val worst = counts.maxByOrNull { it.value }!!
                        "Больше всего пропусков в цилиндре ${worst.key}: ${worst.value}. " +
                            "Проверьте катушку и свечу этого цилиндра (можно переставить катушку на соседний и посмотреть, переедут ли пропуски)."
                    }
                }
                misfires.value = MisfireResult(counts, msg, System.currentTimeMillis())
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                misfires.value = MisfireResult(null, "Ошибка: ${ex.message}", System.currentTimeMillis())
            } finally {
                pollPaused = false
                misfireBusy.value = false
            }
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
        e.restoreHeader()
        addResearch("=== Готово. Ответили идентификаторов Mode 21: $found ===")
        saveResearch()
    }

    /** Блоки в сети CAN, к которым можно обратиться через ELM327 (запрос → ответ). */
    private val modules = listOf(
        Triple("Двигатель (параметры 40–7F)", "7E0", "7E8"),
        Triple("Коробка передач", "7E1", "7E9"),
        Triple("ABS / VSC", "7B0", "7B8"),
        Triple("Электроусилитель руля", "7A1", "7A9"),
        Triple("Подушки безопасности", "780", "788"),
        Triple("Щиток приборов", "7C0", "7C8"),
        Triple("Климат", "7C4", "7CC"),
        Triple("Шлюз / кузов", "750", "758"),
    )

    @Volatile
    private var scanCancel = false

    fun cancelScan() {
        scanCancel = true
    }

    /**
     * Ищет блоки в сети CAN и перебирает их идентификаторы Mode 21.
     * Только чтение: запросы данных, идентификации и кодов ошибок.
     */
    fun scanModules() = researchOp { e ->
        scanCancel = false
        addResearch("=== Поиск блоков ===")
        fun ok(r: String) = r.isNotBlank() && !r.contains("NO DATA") && !r.contains("NODATA") &&
            !r.contains("?") && !r.contains("ERROR") && !r.contains("STOPPED")
        suspend fun q(cmd: String, timeout: Long = 1500): String? = try {
            e.send(cmd, timeout)
        } catch (_: ElmTimeoutException) {
            null
        }
        q("ATH1")
        var alive = 0
        try {
            for ((name, tx, rx) in modules) {
                if (scanCancel) break
                q("ATSH$tx")
                q("ATCRA$rx")
                val engine = tx == "7E0"
                addResearch("--- $name ($tx → $rx) ---")
                // жив ли блок: идентификация, коды, первый идентификатор
                var answered = engine
                for (cmd in listOf("1A87", "1A9B", "2101", "13", "3E00")) {
                    val r = q(cmd) ?: continue
                    if (ok(r)) {
                        answered = true
                        addResearch("> $cmd\n$r")
                    }
                }
                if (!answered) {
                    addResearch("не отвечает")
                    continue
                }
                alive++
                val range = if (engine) 0x40..0x7F else 0x01..0xFF
                var found = 0
                for (id in range) {
                    if (scanCancel) break
                    if (id % 16 == 0) status.value = "Поиск блоков: $name, 21%02X…".format(id)
                    val cmd = String.format(Locale.US, "21%02X", id)
                    val r = q(cmd) ?: continue
                    if (ok(r) && !r.replace(" ", "").contains("7F21")) {
                        found++
                        addResearch("> $cmd\n$r")
                    }
                }
                addResearch("ответили идентификаторов: $found")
            }
        } finally {
            e.restoreHeader()
            status.value = "Подключено · ${e.protocolName}"
        }
        addResearch(if (scanCancel) "=== Остановлено ===" else "=== Готово. Ответили блоков: $alive ===")
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
        val useGps = gpsOn.value && gps.hasPermission()
        val keys = Pids.DISPLAY.map { it.key } + (if (useGps) listOf("lat", "lon") else emptyList()) +
            (if (tcmOk) Pids.TCM_RAW_KEYS else emptyList())
        val l = CsvLogger(logsDir(), keys)
        logger = l
        recordingFile.value = l.file
        liveTrip.value = LiveTrip(System.currentTimeMillis())
        if (useGps) gps.start()
    }

    private fun stopRecordingInternal() {
        val l = logger ?: return
        logger = null
        autoStartedRecording = false
        l.close()
        gps.stop()
        liveTrip.value = null
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
        val r = DtcResult(stored, pending, permanent, System.currentTimeMillis())
        dtcResult.value = r
        rememberDtcs(r)
        dtcMessage.value = if (stored.isEmpty() && pending.isEmpty() && permanent.isEmpty()) "Ошибок нет" else null
    }

    fun readFreeze() = dtcOp { e ->
        val ff = e.readFreezeFrame()
        freezeFrame.value = ff
        if (ff.dtc == null) dtcMessage.value = "Стоп-кадра нет"
    }

    fun clearDtc() = dtcOp { e ->
        val ok = e.clearDtcs()
        if (ok) rememberDtcs(DtcResult(emptyList(), emptyList(), emptyList(), 0))
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
