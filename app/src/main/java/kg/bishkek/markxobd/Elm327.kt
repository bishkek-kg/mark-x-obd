package kg.bishkek.markxobd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

class ElmTimeoutException(cmd: String) : Exception("нет ответа на $cmd")

/** Работа с адаптером ELM327 поверх любого транспорта. */
class Elm327(private val t: Transport, private val log: (String) -> Unit) {
    private val mutex = Mutex()
    var version: String = "ELM327"
        private set
    var isCan: Boolean = true
        private set

    suspend fun send(cmd: String, timeoutMs: Long = 3000): String = mutex.withLock {
        // выбрасываем «хвосты» от предыдущих ответов
        while (true) {
            t.incoming.tryReceive().getOrNull() ?: break
        }
        log("> $cmd")
        t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val done = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = t.incoming.receive()
                sb.append(String(chunk, Charsets.US_ASCII))
                if (sb.indexOf(">") >= 0) break
            }
            true
        }
        val raw = sb.toString()
        log("< " + raw.replace("\r", " ").replace("\n", " ").replace(">", "").trim().take(160))
        if (done == null) throw ElmTimeoutException(cmd)
        clean(raw, cmd)
    }

    private fun clean(raw: String, cmd: String): String =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .filterNot { it.startsWith("SEARCHING", ignoreCase = true) || it.startsWith("BUS INIT", ignoreCase = true) }
            .joinToString("\n")
            .uppercase(Locale.US)

    suspend fun initialize() {
        try {
            send("ATZ", 5000)
        } catch (_: ElmTimeoutException) {
            // некоторые клоны молчат на ATZ — пробуем дальше
        }
        delay(600)
        send("ATE0")
        send("ATL0")
        send("ATS0")
        send("ATH0")
        send("ATAT1")
        version = try {
            send("ATI").lines().firstOrNull() ?: "ELM327"
        } catch (_: ElmTimeoutException) {
            "ELM327"
        }
        // Mark X 2005: ISO 15765-4 CAN 11 бит 500 кбит/с — протокол 6. Если нет — автоопределение.
        send("ATSP6")
        var r = try { send("0100", 8000) } catch (_: ElmTimeoutException) { "" }
        if (!r.replace(" ", "").contains("4100")) {
            send("ATSP0")
            r = try { send("0100", 15000) } catch (_: ElmTimeoutException) { "" }
            if (!r.replace(" ", "").contains("4100")) {
                throw IOException("ЭБУ не отвечает. Включено ли зажигание? Ответ: ${r.ifBlank { "пусто" }}")
            }
        }
        val dp = try { send("ATDPN") } catch (_: ElmTimeoutException) { "" }
        val proto = dp.trim().trimStart('A')
        isCan = proto in setOf("6", "7", "8", "9")
    }

    /** Повторная настройка после возможного сброса адаптера (просадка напряжения при запуске). */
    suspend fun lightReinit() {
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) {
            try {
                send(cmd, 1500)
            } catch (_: ElmTimeoutException) {
            }
        }
    }

    /** Номера поддерживаемых PID Mode 01. */
    suspend fun supportedPids(): Set<Int> {
        val set = HashSet<Int>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60)) {
            if (base != 0 && base !in set) break
            val r = try {
                send(String.format(Locale.US, "01%02X", base), 4000)
            } catch (_: ElmTimeoutException) {
                break
            }
            val d = Pids.extract(r, 1, base) ?: break
            for (i in 0 until 32) {
                val byte = d.getOrNull(i / 8) ?: break
                if (byte and (0x80 shr (i % 8)) != 0) set.add(base + i + 1)
            }
        }
        return set
    }

    suspend fun readPid(p: PidDef): Double? {
        val r = send(String.format(Locale.US, "01%02X", p.pid), 1500)
        val d = Pids.extract(r, 1, p.pid) ?: return null
        return p.decode(d)
    }

    suspend fun adapterVoltage(): Double? {
        val r = send("ATRV", 1500)
        return Regex("([0-9]+(?:\\.[0-9]+)?)").find(r)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    suspend fun readDtcs(mode: Int): List<String> {
        val r = send(String.format(Locale.US, "%02X", mode), 6000)
        if (r.replace(" ", "").contains("NODATA")) return emptyList()
        return Dtc.parse(r, mode + 0x40, isCan)
    }

    suspend fun clearDtcs(): Boolean {
        val r = send("04", 8000)
        return r.replace(" ", "").contains("44")
    }

    suspend fun readFreezeFrame(): FreezeFrame {
        val r = send("020200", 3000)
        val d = Pids.extract(r, 2, 0x02, "00")
        val dtc = if (d != null && d.size >= 2 && (d[0] != 0 || d[1] != 0)) Dtc.decode(d[0], d[1]) else null
        if (dtc == null) return FreezeFrame(null, emptyList())
        val items = ArrayList<Pair<String, String>>()
        for (p in Pids.ALL) {
            if (p.pid !in Pids.FREEZE_PIDS) continue
            val rr = try {
                send(String.format(Locale.US, "02%02X00", p.pid), 1500)
            } catch (_: ElmTimeoutException) {
                continue
            }
            val dd = Pids.extract(rr, 2, p.pid, "00") ?: continue
            val v = p.decode(dd) ?: continue
            items.add(p.title to "${Pids.format(p, v)} ${p.unit}")
        }
        return FreezeFrame(dtc, items)
    }
}
