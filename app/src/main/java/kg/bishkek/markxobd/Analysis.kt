package kg.bishkek.markxobd

import java.io.File
import java.util.Locale

private fun a0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun a1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun a2(v: Double) = String.format(Locale.US, "%.2f", v)

/** Порог, ниже которого на работающем двигателе генератор считается не заряжающим. */
const val CHARGE_LOW_V = 13.0

/** Ниже этой температуры включения вентиляторов не ищем. */
const val FAN_MIN_COOLANT = 60.0

// ======================= Термостат =======================

/**
 * Определяет открытие термостата по единственному датчику ОЖ.
 * Признаки: устойчивая «полка» (4 мин в коридоре 1 °C) после прогрева
 * или — только на стоянке — «ступенька» вниз на 2+ °C: в двигатель пришла холодная жидкость из радиатора.
 * На ходу температура колеблется от нагрузки, поэтому там «ступеньку» не ищем.
 */
class ThermostatDetector {
    private val buf = ArrayDeque<Pair<Long, Double>>()
    private var minSeen: Double? = null
    private var firstSeen: Double? = null
    private var lastMovingAt = 0L

    var openedTemp: Double? = null
        private set
    var openedAt: Long? = null
        private set
    var byDip = false
        private set

    /** Возвращает true в момент, когда открытие обнаружено. */
    fun feed(t: Long, coolant: Double, rpm: Double?, speed: Double? = null): Boolean {
        if (openedAt != null) return false
        if (rpm != null && rpm < 300) {
            buf.clear()
            return false
        }
        if (firstSeen == null) firstSeen = coolant
        minSeen = minOf(minSeen ?: coolant, coolant)
        if (speed != null && speed > 3) lastMovingAt = t
        buf.addLast(t to coolant)
        while (buf.isNotEmpty() && t - buf.first().first > 300_000) buf.removeFirst()

        // прогрет: вырос на 10+ °C от минимума или запись начата уже на горячем двигателе
        val warmedUp = coolant >= 75 && (coolant - (minSeen ?: coolant) >= 10 || (firstSeen ?: 0.0) >= 75)
        if (!warmedUp) return false

        // «ступенька» вниз за последнюю минуту — только если всю минуту стояли
        val recent = buf.filter { it.first >= t - 60_000 }
        val peak = recent.maxByOrNull { it.second }
        val parked = t - lastMovingAt >= 60_000
        if (parked && peak != null && peak.second >= 75 && peak.second - coolant >= 2.0) {
            openedTemp = peak.second
            openedAt = peak.first
            byDip = true
            return true
        }
        // «полка»
        val window = buf.filter { it.first >= t - 240_000 }
        if (window.isNotEmpty() && t - window.first().first >= 230_000) {
            val mx = window.maxOf { it.second }
            val mn = window.minOf { it.second }
            if (mx - mn <= 1.0) {
                openedTemp = window.map { it.second }.average()
                openedAt = window.first().first
                return true
            }
        }
        return false
    }
}

// ======================= Вентиляторы =======================

/**
 * Включение вентиляторов радиатора по ступенчатой просадке напряжения.
 * Чтобы не путать с газовкой и сменой холостых: обороты должны быть ровными,
 * а напряжение после ступеньки — не ниже 13 В. Ниже 60 °C не ищем: на холодном двигателе
 * ступеньки дают переходы холостых. Включение заметно раньше 90 °C — само по себе находка
 * (кондиционер, режим обдува стекла или неисправность управления вентиляторами).
 */
class FanDetector {
    private data class S(val t: Long, val v: Double, val rpm: Double)

    private val buf = ArrayDeque<S>()
    private var lastAt = 0L
    var lastDrop = 0.0
        private set

    fun feed(t: Long, v: Double?, rpm: Double?, coolant: Double?): Boolean {
        if (v == null || rpm == null) return false
        buf.addLast(S(t, v, rpm))
        while (buf.isNotEmpty() && t - buf.first().t > 15_000) buf.removeFirst()
        if (coolant == null || coolant < FAN_MIN_COOLANT || rpm < 400) return false
        if (t - lastAt < 30_000) return false
        val before = buf.filter { it.t in (t - 12_000)..(t - 4_000) }
        val after = buf.filter { it.t > t - 3_000 }
        if (before.size < 3 || after.size < 2) return false
        val rpms = (before + after).map { it.rpm }
        if (rpms.max() - rpms.min() > 100) return false
        val afterV = median(after.map { it.v })
        val drop = median(before.map { it.v }) - afterV
        // если напряжение упало ниже 13 В — это скорее генератор перестал заряжать, а не вентиляторы
        if (drop >= 0.15 && afterV >= CHARGE_LOW_V) {
            lastAt = t
            lastDrop = drop
            return true
        }
        return false
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

// ======================= Зарядка =======================

/** Следит, заряжает ли генератор: на работающем двигателе напряжение должно быть ≥ 13 В. */
class ChargeMonitor {
    private var lowSince: Long? = null
    private var alerted = false
    private var lastT: Long? = null
    var totalLowMs = 0L
        private set
    var isLow = false
        private set

    /** Возвращает true, когда низкое напряжение держится 30 с — пора предупредить. */
    fun feed(t: Long, v: Double?, rpm: Double?): Boolean {
        val dt = lastT?.let { (t - it).coerceIn(0, 10_000) } ?: 0L
        lastT = t
        val running = rpm != null && rpm > 500
        if (running && v != null && v < CHARGE_LOW_V) {
            val since = lowSince ?: t.also { lowSince = it }
            if (t - since >= 30_000) {
                isLow = true
                totalLowMs += dt
                if (!alerted) {
                    alerted = true
                    return true
                }
            }
        } else {
            lowSince = null
            alerted = false
            isLow = false
        }
        return false
    }

    fun reset() {
        lowSince = null
        alerted = false
        lastT = null
        totalLowMs = 0
        isLow = false
    }
}

// ======================= Записи поездок =======================

data class Mark(val time: Long, val label: String, val coolant: Double?)

data class TripMark(val minute: Double, val clock: String, val label: String, val coolant: Double?)

/** Разобранная запись поездки: время в секундах от начала и столбцы значений. */
class TripData(
    val file: File,
    val clock: List<String>,
    val t: DoubleArray,
    private val cols: Map<String, Array<Double?>>,
    val fan: BooleanArray,
    val marks: List<TripMark>,
) {
    val size get() = t.size
    val durationMin: Double get() = if (t.isEmpty()) 0.0 else t.last() / 60.0

    fun col(key: String): Array<Double?>? = cols[key]

    /** Ряд (минуты от начала, значение) для графика. */
    fun series(key: String): List<Pair<Float, Float>> {
        val c = cols[key] ?: return emptyList()
        val out = ArrayList<Pair<Float, Float>>()
        for (i in t.indices) {
            val v = c[i] ?: continue
            out.add((t[i] / 60.0).toFloat() to v.toFloat())
        }
        return out
    }

    /** Напряжение: ЭБУ, а если его нет — адаптер. */
    fun voltageSeries(): List<Pair<Float, Float>> =
        series("ecuV").ifEmpty { series(Pids.ADAPTER_V) }

    companion object {
        fun load(file: File): TripData? {
            val lines = try {
                file.readLines()
            } catch (_: Exception) {
                return null
            }
            if (lines.isEmpty()) return null
            val header = lines.first().split(",")
            val iTime = header.indexOf("time")
            val iEl = header.indexOf("elapsed_s")
            val iFan = header.indexOf("fan_event")
            val iMark = header.indexOf("mark")
            if (iEl < 0) return null
            val numCols = header.withIndex()
                .filter { it.index != iTime && it.index != iEl && it.index != iFan && it.index != iMark }
            val tList = ArrayList<Double>()
            val clock = ArrayList<String>()
            val fan = ArrayList<Boolean>()
            val colLists = numCols.associate { it.value to ArrayList<Double?>() }
            val marks = ArrayList<TripMark>()
            val iCool = header.indexOf("coolant")
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val c = line.split(",")
                val t = c.getOrNull(iEl)?.toDoubleOrNull() ?: continue
                tList.add(t)
                clock.add(if (iTime >= 0) c.getOrNull(iTime) ?: "" else "")
                fan.add(iFan >= 0 && c.getOrNull(iFan) == "1")
                for ((idx, name) in numCols) colLists[name]!!.add(c.getOrNull(idx)?.toDoubleOrNull())
                if (iMark >= 0) {
                    val m = c.getOrNull(iMark)?.trim().orEmpty()
                    if (m.isNotEmpty()) {
                        marks.add(
                            TripMark(
                                t / 60.0,
                                if (iTime >= 0) c.getOrNull(iTime) ?: "" else "",
                                m,
                                if (iCool >= 0) c.getOrNull(iCool)?.toDoubleOrNull() else null,
                            ),
                        )
                    }
                }
            }
            return TripData(
                file,
                clock,
                tList.toDoubleArray(),
                colLists.mapValues { it.value.toTypedArray() },
                fan.toBooleanArray(),
                marks,
            )
        }
    }
}

data class TripStats(
    val durationMin: Double,
    val distKm: Double,
    val maxSpeed: Double,
    val idleMin: Double,
    val maxCoolant: Double?,
    val above100Min: Double,
    val above105Min: Double,
    val plateau: Double?,
    val fanTemps: List<Double>,
    val minV: Double?,
    val maxV: Double?,
    val chargeLowMin: Double,
)

data class TripSummary(val text: String, val stats: TripStats? = null)

object TripReport {
    fun stats(d: TripData): TripStats {
        val cool = d.col("coolant")
        val rpm = d.col("rpm")
        val speed = d.col("speed")
        val v = d.col("ecuV") ?: d.col(Pids.ADAPTER_V)
        var maxCool: Double? = null
        var above100 = 0.0
        var above105 = 0.0
        var dist = 0.0
        var maxSpeed = 0.0
        var idle = 0.0
        var minV: Double? = null
        var maxV: Double? = null
        val fanTemps = ArrayList<Double>()
        val thermo = ThermostatDetector()
        val charge = ChargeMonitor()
        for (i in d.t.indices) {
            val dt = if (i == 0) 0.0 else (d.t[i] - d.t[i - 1]).coerceIn(0.0, 10.0)
            val c = cool?.get(i)
            val r = rpm?.get(i)
            val sp = speed?.get(i)
            val vv = v?.get(i)
            val ms = (d.t[i] * 1000).toLong()
            if (c != null) {
                maxCool = maxOf(maxCool ?: c, c)
                if (c >= 100) above100 += dt
                if (c >= Pids.OVERHEAT_C) above105 += dt
                thermo.feed(ms, c, r, sp)
            }
            if (sp != null) {
                maxSpeed = maxOf(maxSpeed, sp)
                dist += sp * dt / 3600.0
                if (sp < 1 && (r ?: 0.0) > 400) idle += dt
            }
            if (vv != null && (r == null || r > 500)) {
                minV = minOf(minV ?: vv, vv)
                maxV = maxOf(maxV ?: vv, vv)
            }
            charge.feed(ms, vv, r)
            if (d.fan[i] && c != null) fanTemps.add(c)
        }
        return TripStats(
            durationMin = d.durationMin,
            distKm = dist,
            maxSpeed = maxSpeed,
            idleMin = idle / 60,
            maxCoolant = maxCool,
            above100Min = above100 / 60,
            above105Min = above105 / 60,
            plateau = thermo.openedTemp,
            fanTemps = fanTemps,
            minV = minV,
            maxV = maxV,
            chargeLowMin = charge.totalLowMs / 60_000.0,
        )
    }

    fun fromCsv(file: File): TripSummary? {
        val d = TripData.load(file) ?: return null
        if (d.size == 0) return TripSummary("Запись пустая.")
        return summarize(d)
    }

    fun summarize(d: TripData): TripSummary {
        val s = stats(d)
        val sb = StringBuilder()
        sb.append("Поездка ${d.file.nameWithoutExtension.removePrefix("trip_")}\n")
        sb.append("Длительность: ${a0(s.durationMin)} мин, пробег ≈ ${a1(s.distKm)} км, макс. скорость ${a0(s.maxSpeed)} км/ч, на холостых ${a0(s.idleMin)} мин\n")
        s.maxCoolant?.let {
            sb.append("Температура ОЖ: макс. ${a0(it)} °C")
            if (s.above100Min > 0) sb.append(", выше 100 °C — ${a1(s.above100Min)} мин")
            if (s.above105Min > 0) sb.append(", выше 105 °C — ${a1(s.above105Min)} мин")
            sb.append('\n')
        }
        s.plateau?.let { sb.append("Термостат: температура встала на «полку» около ${a0(it)} °C\n") }
        if (s.fanTemps.isNotEmpty()) {
            sb.append("Вероятные включения вентиляторов: ${s.fanTemps.size} (при ${s.fanTemps.joinToString { a0(it) }} °C)\n")
        }
        if (s.minV != null && s.maxV != null) {
            sb.append("Напряжение на работающем двигателе: ${a2(s.minV)}–${a2(s.maxV)} В\n")
        }
        if (s.chargeLowMin >= 0.1) {
            sb.append("Генератор не заряжал (ниже ${a1(CHARGE_LOW_V)} В): ${a1(s.chargeLowMin)} мин — проверьте генератор, ремень, клеммы\n")
        }
        if (d.marks.isNotEmpty()) {
            sb.append("Метки:\n")
            d.marks.forEach { m ->
                sb.append("  ${m.clock} — ${m.label}")
                m.coolant?.let { sb.append(" (ОЖ ${a0(it)} °C)") }
                sb.append('\n')
            }
        }
        val mx = s.maxCoolant
        sb.append(
            when {
                mx == null -> "Данных о температуре нет."
                mx >= Pids.OVERHEAT_C -> "Был перегрев — без диагностики лучше не ездить."
                mx >= 100 -> "Температура поднималась выше нормы."
                else -> "Температура в норме."
            },
        )
        return TripSummary(sb.toString(), s)
    }
}
