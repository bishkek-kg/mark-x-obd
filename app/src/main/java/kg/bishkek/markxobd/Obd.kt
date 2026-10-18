package kg.bishkek.markxobd

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Описание параметра Mode 01. pid = -1 — напряжение адаптера (ATRV). */
data class PidDef(
    val key: String,
    val pid: Int,
    val title: String,
    val unit: String,
    val decimals: Int,
    /** Опрашивать каждый цикл (иначе — каждый третий). */
    val priority: Boolean,
    val decode: (IntArray) -> Double?,
)

private fun IntArray.b(i: Int): Int? = getOrNull(i)
private fun IntArray.word(): Int? {
    val a = b(0) ?: return null
    val c = b(1) ?: return null
    return a * 256 + c
}

/** Пороги предупреждений и цена топлива — настраиваются на вкладке «Связь». */
object Thresholds {
    @Volatile var overheat = 105.0
    @Volatile var warn = 100.0
    @Volatile var lowV = 13.0
    /** 0 — предупреждение по оборотам выключено. */
    @Volatile var rpmMax = 0.0
    /** Цена литра бензина, сом. 0 — стоимость не считать. */
    @Volatile var fuelPrice = 0.0
}

/** Расход топлива по MAF: стехиометрия 14,7 и плотность бензина 745 г/л. Без лямбд — оценка. */
object Fuel {
    fun litersPerHour(mafGs: Double): Double = mafGs * 3600.0 / (14.7 * 745.0)

    /**
     * Расход воздуха из расчётной нагрузки, когда блок не отдаёт MAF.
     * 4GR-FSE: 2,494 л; плотность воздуха 1,184 г/л. Поправка 1,02 подобрана по записям этой машины,
     * где были и MAF, и нагрузка (медиана отношения 1,02, разброс 0,8–1,35 в переходах).
     */
    fun mafFromLoad(loadPct: Double, rpm: Double): Double = loadPct / 100.0 * rpm / 120.0 * 2.494 * 1.184 * 1.02

    fun airflow(maf: Double?, load: Double?, rpm: Double?): Double? =
        maf ?: if (load != null && rpm != null) mafFromLoad(load, rpm) else null
}

object Pids {
    const val ADAPTER_V = "adapterV"
    const val FUEL_LH = "fuelLh"
    const val FUEL_L100 = "fuelL100"
    val OVERHEAT_C: Double get() = Thresholds.overheat

    val ALL: List<PidDef> = listOf(
        PidDef("coolant", 0x05, "Температура ОЖ", "°C", 0, true) { d -> d.b(0)?.let { it - 40.0 } },
        PidDef("ecuV", 0x42, "Напряжение ЭБУ", "В", 2, true) { d -> d.word()?.let { it / 1000.0 } },
        PidDef("rpm", 0x0C, "Обороты", "об/мин", 0, true) { d -> d.word()?.let { it / 4.0 } },
        PidDef("speed", 0x0D, "Скорость", "км/ч", 0, true) { d -> d.b(0)?.toDouble() },
        PidDef("load", 0x04, "Нагрузка", "%", 0, false) { d -> d.b(0)?.let { it * 100.0 / 255.0 } },
        PidDef("iat", 0x0F, "Темп. воздуха на впуске", "°C", 0, false) { d -> d.b(0)?.let { it - 40.0 } },
        PidDef("ambient", 0x46, "Темп. снаружи", "°C", 0, false) { d -> d.b(0)?.let { it - 40.0 } },
        PidDef("oilTemp", 0x5C, "Темп. масла", "°C", 0, false) { d -> d.b(0)?.let { it - 40.0 } },
        PidDef("stft1", 0x06, "Краткоср. коррекция Б1", "%", 1, false) { d -> d.b(0)?.let { (it - 128) * 100.0 / 128.0 } },
        PidDef("ltft1", 0x07, "Долгоср. коррекция Б1", "%", 1, false) { d -> d.b(0)?.let { (it - 128) * 100.0 / 128.0 } },
        PidDef("stft2", 0x08, "Краткоср. коррекция Б2", "%", 1, false) { d -> d.b(0)?.let { (it - 128) * 100.0 / 128.0 } },
        PidDef("ltft2", 0x09, "Долгоср. коррекция Б2", "%", 1, false) { d -> d.b(0)?.let { (it - 128) * 100.0 / 128.0 } },
        PidDef("maf", 0x10, "Расход воздуха (MAF)", "г/с", 2, false) { d -> d.word()?.let { it / 100.0 } },
        PidDef("throttle", 0x11, "Дроссель", "%", 0, false) { d -> d.b(0)?.let { it * 100.0 / 255.0 } },
        PidDef("timing", 0x0E, "Угол опережения", "°", 1, false) { d -> d.b(0)?.let { it / 2.0 - 64.0 } },
        PidDef("map", 0x0B, "Давление во впуске", "кПа", 0, false) { d -> d.b(0)?.toDouble() },
        PidDef("railP", 0x23, "Давление топлива в рампе", "МПа", 1, false) { d -> d.word()?.let { it * 10.0 / 1000.0 } },
        PidDef("lambda1", 0x24, "Лямбда, датчик 1", "λ", 3, false) { d -> d.word()?.let { it * 2.0 / 65536.0 } },
        PidDef("lambda5", 0x28, "Лямбда, датчик 5", "λ", 3, false) { d -> d.word()?.let { it * 2.0 / 65536.0 } },
    )

    val ADAPTER = PidDef(ADAPTER_V, -1, "Напряжение адаптера", "В", 1, false) { null }

    /** Расчётные параметры: не опрашиваются, считаются из других (pid = -2). */
    /** АКПП A760E (блок 7E1): кандидаты на температуру масла — какой верный, покажут записи с холодного пуска. */
    const val ATF_A = "atfA"
    const val ATF_B = "atfB"
    val TCM_RAW_KEYS: List<String> =
        (0 until 6).map { "tB4_$it" } + (0 until 9).map { "tB6_$it" } + (0 until 8).map { "tD9_$it" } + (0 until 5).map { "tE2_$it" }

    val DERIVED: List<PidDef> = listOf(
        PidDef(ATF_A, -3, "Темп. масла АКПП (проверяется)", "°C", 0, false) { null },
        PidDef(FUEL_LH, -2, "Расход топлива", "л/ч", 1, false) { null },
        PidDef(FUEL_L100, -2, "Расход на 100 км", "л/100км", 1, false) { null },
    )

    val DISPLAY: List<PidDef> = ALL + ADAPTER + DERIVED

    /** Параметры, которые читаем в стоп-кадре (Mode 02). */
    val FREEZE_PIDS = setOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x42)

    fun byKey(key: String): PidDef? = DISPLAY.firstOrNull { it.key == key }

    fun format(p: PidDef, v: Double): String = String.format(Locale.US, "%.${p.decimals}f", v)

    /**
     * Достаёт байты данных из ответа ELM327 для запроса [mode]/[pid].
     * extra — дополнительный префикс ответа (номер кадра "00" для Mode 02).
     */
    fun extract(resp: String, mode: Int, pid: Int, extra: String = ""): IntArray? {
        val prefix = String.format(Locale.US, "%02X%02X", mode + 0x40, pid) + extra
        for (line in resp.lines()) {
            val l = line.replace(" ", "").trim().uppercase(Locale.US)
            var from = 0
            while (true) {
                val idx = l.indexOf(prefix, from)
                if (idx < 0) break
                if (idx % 2 == 0) {
                    val bytes = hexBytes(l.substring(idx + prefix.length))
                    if (bytes.isNotEmpty()) return bytes
                }
                from = idx + 1
            }
        }
        return null
    }

    fun hexBytes(s: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 1 < s.length) {
            val v = s.substring(i, i + 2).toIntOrNull(16) ?: break
            out.add(v)
            i += 2
        }
        return out.toIntArray()
    }
}

data class DtcResult(
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val time: Long,
)

data class FreezeFrame(val dtc: String?, val items: List<Pair<String, String>>)

data class FanEvent(val time: Long, val coolant: Double?, val drop: Double)

data class DeviceItem(val name: String, val address: String, val ble: Boolean)

object Dtc {
    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val d1 = (a shr 4) and 3
        return letter + d1.toString() + String.format(Locale.US, "%X%02X", a and 0x0F, b)
    }

    /** Разбор ответа на 03/07/0A с учётом многокадровых ответов CAN (ISO-TP). */
    fun parse(resp: String, service: Int, can: Boolean): List<String> {
        val messages = ArrayList<StringBuilder>()
        val frame = Regex("^([0-9A-F]):(.*)$")
        for (raw in resp.lines()) {
            val l = raw.replace(" ", "").trim().uppercase(Locale.US)
            if (l.isEmpty()) continue
            if (!l.all { it.isDigit() || it in 'A'..'F' || it == ':' }) continue
            val m = frame.find(l)
            if (m != null) {
                if (m.groupValues[1] == "0" || messages.isEmpty()) messages.add(StringBuilder())
                messages.last().append(m.groupValues[2])
            } else if (l.length == 3) {
                continue // длина многокадрового ответа
            } else {
                messages.add(StringBuilder(l))
            }
        }
        val svc = String.format(Locale.US, "%02X", service)
        val result = LinkedHashSet<String>()
        for (msg in messages) {
            val s = msg.toString()
            val idx = s.indexOf(svc)
            if (idx < 0 || idx % 2 != 0) continue
            var bytes = Pids.hexBytes(s.substring(idx + 2)).toList()
            if (can && bytes.isNotEmpty()) {
                val count = bytes[0]
                bytes = bytes.drop(1).take(count * 2)
            }
            var i = 0
            while (i + 1 < bytes.size) {
                val a = bytes[i]
                val b = bytes[i + 1]
                if (a != 0 || b != 0) result.add(decode(a, b))
                i += 2
            }
        }
        return result.toList()
    }

    private val DESCRIPTIONS = mapOf(
        "P0031" to "Подогрев датчика кислорода (банк 1, датчик 1) — низкий уровень в цепи",
        "P0032" to "Подогрев датчика кислорода (банк 1, датчик 1) — высокий уровень в цепи",
        "P0037" to "Подогрев датчика кислорода (банк 1, датчик 2) — низкий уровень в цепи",
        "P0038" to "Подогрев датчика кислорода (банк 1, датчик 2) — высокий уровень в цепи",
        "P0051" to "Подогрев датчика кислорода (банк 2, датчик 1) — низкий уровень в цепи",
        "P0052" to "Подогрев датчика кислорода (банк 2, датчик 1) — высокий уровень в цепи",
        "P0057" to "Подогрев датчика кислорода (банк 2, датчик 2) — низкий уровень в цепи",
        "P0058" to "Подогрев датчика кислорода (банк 2, датчик 2) — высокий уровень в цепи",
        "P0100" to "Датчик массового расхода воздуха (MAF) — неисправность цепи",
        "P0101" to "Датчик MAF — сигнал вне допустимого диапазона",
        "P0102" to "Датчик MAF — низкий уровень сигнала",
        "P0103" to "Датчик MAF — высокий уровень сигнала",
        "P0110" to "Датчик температуры воздуха на впуске — неисправность цепи",
        "P0112" to "Датчик температуры воздуха на впуске — низкий уровень сигнала",
        "P0113" to "Датчик температуры воздуха на впуске — высокий уровень сигнала",
        "P0115" to "Датчик температуры ОЖ — неисправность цепи",
        "P0116" to "Датчик температуры ОЖ — сигнал вне допустимого диапазона",
        "P0117" to "Датчик температуры ОЖ — низкий уровень сигнала",
        "P0118" to "Датчик температуры ОЖ — высокий уровень сигнала",
        "P0125" to "Двигатель слишком долго не прогревается до рабочей температуры",
        "P0128" to "Температура ОЖ ниже порога термостата (термостат открыт/подклинивает)",
        "P0130" to "Датчик кислорода (банк 1, датчик 1) — неисправность цепи",
        "P0136" to "Датчик кислорода (банк 1, датчик 2) — неисправность цепи",
        "P0150" to "Датчик кислорода (банк 2, датчик 1) — неисправность цепи",
        "P0156" to "Датчик кислорода (банк 2, датчик 2) — неисправность цепи",
        "P0171" to "Слишком бедная смесь (банк 1)",
        "P0172" to "Слишком богатая смесь (банк 1)",
        "P0174" to "Слишком бедная смесь (банк 2)",
        "P0175" to "Слишком богатая смесь (банк 2)",
        "P0300" to "Случайные пропуски зажигания",
        "P0301" to "Пропуски зажигания в цилиндре 1",
        "P0302" to "Пропуски зажигания в цилиндре 2",
        "P0303" to "Пропуски зажигания в цилиндре 3",
        "P0304" to "Пропуски зажигания в цилиндре 4",
        "P0305" to "Пропуски зажигания в цилиндре 5",
        "P0306" to "Пропуски зажигания в цилиндре 6",
        "P0335" to "Датчик положения коленвала — неисправность цепи",
        "P0340" to "Датчик положения распредвала — неисправность цепи",
        "P0351" to "Цепь катушки зажигания 1 (A). Сразу несколько таких кодов — часто питание/масса или просадка напряжения",
        "P0352" to "Цепь катушки зажигания 2 (B)",
        "P0353" to "Цепь катушки зажигания 3 (C)",
        "P0354" to "Цепь катушки зажигания 4 (D)",
        "P0355" to "Цепь катушки зажигания 5 (E)",
        "P0356" to "Цепь катушки зажигания 6 (F)",
        "P0420" to "Низкая эффективность катализатора (банк 1)",
        "P0430" to "Низкая эффективность катализатора (банк 2)",
        "P0441" to "Система улавливания паров топлива (EVAP) — неверный расход продувки",
        "P0500" to "Датчик скорости автомобиля",
        "P0505" to "Система регулирования холостого хода",
        "P0560" to "Напряжение бортовой сети — неисправность",
        "P0562" to "Низкое напряжение бортовой сети",
        "P0563" to "Высокое напряжение бортовой сети",
        "P0606" to "Неисправность процессора ЭБУ",
        "P1603" to "Toyota: запись об остановке (заглохании) двигателя. Обычно не означает неисправность EEPROM",
        "P2195" to "Датчик A/F (банк 1, датчик 1) — сигнал «бедно» залип",
        "P2196" to "Датчик A/F (банк 1, датчик 1) — сигнал «богато» залип",
        "P2197" to "Датчик A/F (банк 2, датчик 1) — сигнал «бедно» залип",
        "P2198" to "Датчик A/F (банк 2, датчик 1) — сигнал «богато» залип",
    )

    fun describe(code: String): String = DESCRIPTIONS[code] ?: when {
        code.startsWith("P0") || code.startsWith("P2") -> "Общий код двигателя/трансмиссии (описание в базе приложения нет)"
        code.startsWith("P1") || code.startsWith("P3") -> "Код производителя (описание в базе приложения нет)"
        code.startsWith("C") -> "Шасси (ABS, подвеска)"
        code.startsWith("B") -> "Кузов (электрика салона, подушки)"
        code.startsWith("U") -> "Сеть обмена данными между блоками"
        else -> "Описание не найдено"
    }
}

/** Скользящая история значений для графиков. */
class History(private val maxAgeMs: Long) {
    private val data = HashMap<String, ArrayDeque<Pair<Long, Float>>>()

    @Synchronized
    fun add(key: String, t: Long, v: Double) {
        val q = data.getOrPut(key) { ArrayDeque() }
        q.addLast(t to v.toFloat())
        while (q.isNotEmpty() && t - q.first().first > maxAgeMs) q.removeFirst()
    }

    @Synchronized
    fun get(key: String, sinceMs: Long): List<Pair<Long, Float>> =
        data[key]?.filter { it.first >= sinceMs } ?: emptyList()

    @Synchronized
    fun clear() = data.clear()
}

/** Запись поездки в CSV. */
class CsvLogger(dir: File, private val keys: List<String>) {
    private val precise = setOf("lat", "lon")
    val file: File
    private val writer: BufferedWriter
    private val start = System.currentTimeMillis()
    private var rows = 0

    init {
        dir.mkdirs()
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        file = File(dir, "trip_$name.csv")
        writer = file.bufferedWriter()
        writer.write("time,elapsed_s," + keys.joinToString(",") + ",fan_event,mark\n")
    }

    @Synchronized
    fun write(now: Long, values: Map<String, Double>, fanEvent: Boolean, mark: String? = null) {
        val sb = StringBuilder()
        sb.append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now)))
        sb.append(',').append(String.format(Locale.US, "%.1f", (now - start) / 1000.0))
        for (k in keys) {
            sb.append(',')
            values[k]?.let { sb.append(String.format(Locale.US, if (k in precise) "%.6f" else "%.3f", it)) }
        }
        sb.append(',').append(if (fanEvent) "1" else "")
        sb.append(',')
        // запятые и переводы строк в метке сломали бы CSV
        mark?.let { sb.append(it.replace(',', ';').replace('\n', ' ').replace('\r', ' ')) }
        sb.append('\n')
        writer.write(sb.toString())
        if (++rows % 10 == 0) writer.flush()
    }

    @Synchronized
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
    }
}
