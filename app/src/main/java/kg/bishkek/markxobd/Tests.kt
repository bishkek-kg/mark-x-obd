package kg.bishkek.markxobd

import java.io.File
import java.util.Locale

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)

// ======================= Тест аккумулятора =======================

data class BatteryTest(
    val running: Boolean,
    val phase: String,
    val rest: Double? = null,
    val min: Double? = null,
    val charging: Double? = null,
    val samples: List<Pair<Long, Float>> = emptyList(),
    val verdict: String? = null,
)

object BatteryVerdict {
    fun make(rest: Double?, min: Double?, charging: Double?, lostLink: Boolean): String {
        val sb = StringBuilder()
        if (rest != null) {
            sb.append("Покой: ${f2(rest)} В — ")
            sb.append(
                when {
                    rest >= 12.6 -> "аккумулятор заряжен."
                    rest >= 12.4 -> "заряжен почти полностью."
                    rest >= 12.2 -> "заряжен примерно наполовину."
                    else -> "разряжен. Зарядите и повторите тест."
                },
            )
            sb.append('\n')
        }
        if (min != null) {
            sb.append("Минимум при запуске: ${f2(min)} В — ")
            sb.append(
                when {
                    min >= 10.5 -> "отлично, аккумулятор держит пусковой ток."
                    min >= 9.6 -> "в пределах нормы, но запас небольшой."
                    else -> "слишком низко: аккумулятор слабый или изношен. Такая просадка может давать ошибки по катушкам (P035x) и P1603."
                },
            )
            sb.append('\n')
        }
        if (lostLink) {
            sb.append("Адаптер потерял связь во время запуска — обычно это значит, что напряжение просело так сильно, что адаптер перезагрузился. Это признак слабого аккумулятора.\n")
        }
        if (charging != null) {
            sb.append("Зарядка на заведённом: ${f2(charging)} В — ")
            sb.append(
                when {
                    charging < 13.4 -> "низко, генератор может не заряжать."
                    charging <= 14.8 -> "генератор заряжает нормально."
                    else -> "высоко, возможен перезаряд (регулятор генератора)."
                },
            )
            sb.append('\n')
        }
        sb.append("Напряжение меряется на разъёме OBD, на клеммах оно обычно на 0,1–0,3 В выше.")
        return sb.toString()
    }
}

// ======================= Тест прогрева =======================

data class WarmupState(
    val running: Boolean,
    val startTime: Long,
    val startTemp: Double?,
    val current: Double?,
    val max: Double?,
    val ratePerMin: Double?,
    val thermostatTemp: Double?,
    val thermostatAtMin: Double?,
    val fanTemps: List<Double>,
    val elapsedMin: Double,
    val verdict: String?,
)

/** Анализ прогрева на холостых: момент открытия термостата, вентиляторы, максимум. */
class WarmupAnalyzer(private val startTime: Long, private val startTemp: Double?) {
    private val samples = ArrayList<Pair<Long, Double>>()
    private var maxRate = 0.0
    private var max: Double? = null
    private var thermostatTemp: Double? = null
    private var thermostatAt: Long? = null
    private val fanTemps = ArrayList<Double>()
    private var engineOffSince: Long? = null
    var finished = false
        private set
    private var verdict: String? = null

    fun onFan(coolant: Double?) {
        if (!finished && coolant != null) fanTemps.add(coolant)
    }

    fun update(now: Long, coolant: Double?, rpm: Double?): WarmupState {
        if (!finished && coolant != null) {
            samples.add(now to coolant)
            max = maxOf(max ?: coolant, coolant)
            val rate = rateAt(now)
            val elapsed = now - startTime
            if (rate != null && elapsed > 60_000) maxRate = maxOf(maxRate, rate)
            if (thermostatTemp == null && rate != null && elapsed > 120_000 &&
                coolant >= 72.0 && maxRate >= 2.5 && rate <= maxRate * 0.35
            ) {
                thermostatTemp = coolant
                thermostatAt = now
            }
            // двигатель заглох / заглушен
            if (rpm != null && rpm < 300) {
                if (engineOffSince == null) engineOffSince = now
            } else {
                engineOffSince = null
            }
            val off = engineOffSince
            when {
                coolant >= Pids.OVERHEAT_C -> finish("Температура дошла до ${f0(coolant)} °C — тест остановлен. Заглушите двигатель и дайте остыть.")
                off != null && now - off > 10_000 -> finish("Двигатель не работает — тест остановлен.")
                elapsed >= 25 * 60_000L -> finish(null)
                thermostatAt != null && now - thermostatAt!! >= 6 * 60_000L && fanTemps.isNotEmpty() -> finish(null)
                thermostatAt != null && now - thermostatAt!! >= 10 * 60_000L -> finish(null)
            }
        }
        return state(now, coolant)
    }

    fun stopByUser(now: Long, coolant: Double?): WarmupState {
        if (!finished) finish("Тест остановлен вручную.")
        return state(now, coolant)
    }

    private fun rateAt(now: Long): Double? {
        val cur = samples.lastOrNull() ?: return null
        val past = samples.lastOrNull { it.first <= now - 60_000 } ?: return null
        val dtMin = (cur.first - past.first) / 60_000.0
        if (dtMin <= 0.5) return null
        return (cur.second - past.second) / dtMin
    }

    private fun finish(reason: String?) {
        finished = true
        verdict = buildVerdict(reason)
    }

    private fun buildVerdict(reason: String?): String {
        val sb = StringBuilder()
        if (reason != null) sb.append(reason).append('\n')
        val st = startTemp
        if (st != null && st > 50) {
            sb.append("Тест начат не на холодном двигателе (${f0(st)} °C), момент открытия термостата может определиться неточно.\n")
        }
        val tt = thermostatTemp
        val mx = max
        if (tt != null) {
            sb.append("Термостат открылся примерно при ${f0(tt)} °C")
            sb.append(if (tt in 76.0..92.0) " — это нормально.\n" else " — необычно, стоит проверить термостат.\n")
        } else if (mx != null && mx < 75 && (System.currentTimeMillis() - startTime) > 15 * 60_000L) {
            sb.append("За 15+ минут двигатель не прогрелся выше ${f0(mx)} °C — похоже на термостат, открытый или подклинивающий в открытом положении.\n")
        } else {
            sb.append("Момент открытия термостата не определён.\n")
        }
        if (fanTemps.isNotEmpty()) {
            val first = fanTemps.first()
            sb.append("Вентиляторы (вероятно) включались при: ${fanTemps.joinToString { f0(it) }} °C.\n")
            if (first < 90) {
                sb.append("Включение ниже 90 °C — раньше обычного. Проверьте, не включён ли кондиционер, датчик температуры и управление вентиляторами.\n")
            }
        } else {
            sb.append("Включение вентиляторов по напряжению не замечено — сверьте на слух.\n")
        }
        if (mx != null) {
            sb.append("Максимум: ${f0(mx)} °C — ")
            sb.append(
                when {
                    mx >= Pids.OVERHEAT_C -> "перегрев. Без выяснения причины не ездите.\n"
                    mx >= 100 -> "выше обычного для холостых, система охлаждения не справляется полностью.\n"
                    mx >= 80 -> "нормальный уровень.\n"
                    else -> "двигатель так и не вышел на рабочую температуру.\n"
                },
            )
        }
        sb.append("Важно: на холостых нагрузка мала. Если здесь всё в норме, а на ходу греется — смотрите радиатор, помпу и прокладку ГБЦ.")
        return sb.toString()
    }

    private fun state(now: Long, coolant: Double?) = WarmupState(
        running = !finished,
        startTime = startTime,
        startTemp = startTemp,
        current = coolant,
        max = max,
        ratePerMin = rateAt(now),
        thermostatTemp = thermostatTemp,
        thermostatAtMin = thermostatAt?.let { (it - startTime) / 60_000.0 },
        fanTemps = fanTemps.toList(),
        elapsedMin = (now - startTime) / 60_000.0,
        verdict = verdict,
    )
}

// ======================= Сводка поездки =======================

data class TripSummary(val text: String)

object TripReport {
    fun fromCsv(file: File): TripSummary? {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return null
        }
        if (lines.size < 2) return TripSummary("Запись пустая.")
        val header = lines.first().split(",")
        fun col(name: String) = header.indexOf(name)
        val iElapsed = col("elapsed_s")
        val iCool = col("coolant")
        val iEcu = col("ecuV")
        val iAdapter = col(Pids.ADAPTER_V)
        val iSpeed = col("speed")
        val iRpm = col("rpm")
        val iFan = col("fan_event")

        var maxCool: Double? = null
        var minEcu: Double? = null
        var maxEcu: Double? = null
        var maxSpeed = 0.0
        var distKm = 0.0
        var above100s = 0.0
        var fanTemps = ArrayList<Double>()
        var lastT: Double? = null
        var duration = 0.0
        var idleS = 0.0

        for (line in lines.drop(1)) {
            val c = line.split(",")
            fun d(i: Int): Double? = if (i >= 0) c.getOrNull(i)?.toDoubleOrNull() else null
            val t = d(iElapsed) ?: continue
            val dt = lastT?.let { (t - it).coerceIn(0.0, 10.0) } ?: 0.0
            lastT = t
            duration = t
            val cool = d(iCool)
            if (cool != null) {
                maxCool = maxOf(maxCool ?: cool, cool)
                if (cool >= 100) above100s += dt
            }
            val v = d(iEcu) ?: d(iAdapter)
            val rpm = d(iRpm)
            if (v != null && (rpm == null || rpm > 400)) {
                minEcu = minOf(minEcu ?: v, v)
                maxEcu = maxOf(maxEcu ?: v, v)
            }
            val sp = d(iSpeed)
            if (sp != null) {
                maxSpeed = maxOf(maxSpeed, sp)
                distKm += sp * dt / 3600.0
                if (sp < 1 && (rpm ?: 0.0) > 400) idleS += dt
            }
            if (iFan >= 0 && c.getOrNull(iFan) == "1" && cool != null) fanTemps.add(cool)
        }

        val sb = StringBuilder()
        sb.append("Поездка ${file.nameWithoutExtension.removePrefix("trip_")}\n")
        sb.append("Длительность: ${f0(duration / 60)} мин, пробег ≈ ${f1(distKm)} км, макс. скорость ${f0(maxSpeed)} км/ч, на холостых ${f0(idleS / 60)} мин\n")
        maxCool?.let {
            sb.append("Температура ОЖ: макс. ${f0(it)} °C")
            if (above100s > 0) sb.append(", выше 100 °C — ${f1(above100s / 60)} мин")
            sb.append('\n')
        }
        if (fanTemps.isNotEmpty()) {
            sb.append("Вероятные включения вентиляторов: ${fanTemps.size} (при ${fanTemps.joinToString { f0(it) }} °C)\n")
        }
        if (minEcu != null && maxEcu != null) {
            sb.append("Напряжение на ходу: ${f2(minEcu)}–${f2(maxEcu)} В")
            if (minEcu < 13.2) sb.append(" — местами низкое, проверьте зарядку")
            sb.append('\n')
        }
        val verdict = when {
            maxCool == null -> "Данных о температуре нет."
            maxCool >= Pids.OVERHEAT_C -> "Был перегрев — без диагностики лучше не ездить."
            maxCool >= 100 -> "Температура поднималась выше нормы."
            else -> "Температура в норме."
        }
        sb.append(verdict)
        return TripSummary(sb.toString())
    }
}

// ======================= Исследование Toyota =======================

object Research {
    /** Команды, которые могут что-то менять в блоках: запись, управление, программирование, стирание. */
    private val blockedModes = setOf("04", "14", "2E", "2F", "30", "31", "3B", "34", "35", "36", "37", "10", "11", "27", "28", "85")

    fun isBlocked(cmd: String): Boolean {
        val c = cmd.trim().uppercase(Locale.US).replace(" ", "")
        if (c.startsWith("AT") || c.startsWith("ST")) {
            // запрещаем сброс к заводским и смену скорости порта, остальное можно
            return c.startsWith("ATPP") || c.startsWith("ATBRD") || c.startsWith("ATWS")
        }
        if (c.length < 2) return false
        return c.substring(0, 2) in blockedModes
    }
}
