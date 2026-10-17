package kg.bishkek.markxobd

import java.util.Locale

/** Пороги предупреждений, которые пользователь задаёт сам. */
data class ThresholdValues(
    val overheat: Double = 105.0,
    val warn: Double = 100.0,
    val lowV: Double = 13.0,
    val rpmMax: Double = 0.0,
    val fuelPrice: Double = 0.0,
) {
    fun apply() {
        Thresholds.overheat = overheat
        Thresholds.warn = warn
        Thresholds.lowV = lowV
        Thresholds.rpmMax = rpmMax
        Thresholds.fuelPrice = fuelPrice
    }
}

/** Счётчики текущей записи поездки. */
data class LiveTrip(val startedAt: Long, val distKm: Double = 0.0, val fuelL: Double = 0.0) {
    val avgL100: Double? get() = if (distKm >= 0.5) fuelL / distKm * 100.0 else null
    val cost: Double? get() = if (Thresholds.fuelPrice > 0) fuelL * Thresholds.fuelPrice else null
}

data class MisfireResult(val counts: Map<Int, Int>?, val message: String, val time: Long)

data class AccelResult(val time: Long, val t60: Double?, val t100: Double?) {
    fun encode() = "$time|${t60 ?: ""}|${t100 ?: ""}"

    companion object {
        fun decode(s: String): AccelResult? {
            val p = s.split("|")
            if (p.size != 3) return null
            return AccelResult(p[0].toLongOrNull() ?: return null, p[1].toDoubleOrNull(), p[2].toDoubleOrNull())
        }
    }
}

enum class AccelPhase { WAIT_STOP, READY, RUNNING, DONE }

data class AccelState(
    val phase: AccelPhase,
    val message: String,
    val speed: Double? = null,
    val elapsed: Double? = null,
    val t60: Double? = null,
    val t100: Double? = null,
)

/**
 * Замер разгона 0–60 и 0–100 км/ч по скорости из блока.
 * Время пересечения отметок уточняется линейной интерполяцией между замерами.
 */
class AccelRun {
    private var phase = AccelPhase.WAIT_STOP
    private var lastZeroT = 0L
    private var startT = 0L
    private var prevT = 0L
    private var prevS = 0.0
    private var maxS = 0.0
    private var t60: Double? = null
    private var t100: Double? = null

    val active: Boolean get() = phase == AccelPhase.READY || phase == AccelPhase.RUNNING

    fun feed(t: Long, speed: Double?): AccelState {
        if (speed == null) return state(null, t)
        when (phase) {
            AccelPhase.WAIT_STOP -> if (speed < 1) {
                phase = AccelPhase.READY
                lastZeroT = t
            }
            AccelPhase.READY -> if (speed < 1) {
                lastZeroT = t
            } else {
                // момент трогания — посередине между последним нулём и первой ненулевой скоростью
                startT = (lastZeroT + t) / 2
                phase = AccelPhase.RUNNING
                prevT = startT
                prevS = 0.0
                maxS = 0.0
                cross(t, speed)
            }
            AccelPhase.RUNNING -> {
                cross(t, speed)
                val el = (t - startT) / 1000.0
                if (t100 != null || speed < maxS - 8 || el > 40) phase = AccelPhase.DONE
            }
            AccelPhase.DONE -> {}
        }
        return state(speed, t)
    }

    private fun cross(t: Long, s: Double) {
        fun at(target: Double): Double {
            val frac = if (s > prevS) (target - prevS) / (s - prevS) else 1.0
            return ((prevT + (t - prevT) * frac.coerceIn(0.0, 1.0)) - startT) / 1000.0
        }
        if (t60 == null && s >= 60) t60 = at(60.0)
        if (t100 == null && s >= 100) t100 = at(100.0)
        maxS = maxOf(maxS, s)
        prevT = t
        prevS = s
    }

    fun result(now: Long): AccelResult? =
        if (t60 != null || t100 != null) AccelResult(now, t60, t100) else null

    private fun state(speed: Double?, t: Long): AccelState {
        val el = if (phase == AccelPhase.RUNNING) (t - startT) / 1000.0 else null
        val msg = when (phase) {
            AccelPhase.WAIT_STOP -> "Остановитесь полностью"
            AccelPhase.READY -> "Готово. Разгоняйтесь, когда безопасно"
            AccelPhase.RUNNING -> "Разгон…"
            AccelPhase.DONE -> if (t60 == null && t100 == null) "Замер прерван — до 60 км/ч не разогнались" else "Готово"
        }
        return AccelState(phase, msg, speed, el, t60, t100)
    }
}

fun secs(v: Double?): String = v?.let { String.format(Locale.US, "%.1f с", it) } ?: "—"
