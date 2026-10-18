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

    /** Сервис чтения параметров: 0x01 — стандарт OBD2, 0x21 — японский Toyota JOBD. */
    var pidMode: Int = 0x01
        private set

    /** Сервис стоп-кадра: 0x02 для OBD2, 0x12 для JOBD. */
    private val freezeMode: Int get() = if (pidMode == 0x21) 0x12 else 0x02

    val protocolName: String get() = if (pidMode == 0x21) "Toyota JOBD" else "OBD2"

    /** Блок двигателя отвечает только на адресные запросы 7E0 (а не на общий 7DF). */
    var addressed: Boolean = false
        private set

    /** Вернуть рабочий заголовок после экспериментов с другими блоками. */
    suspend fun restoreHeader() {
        try { send(if (addressed) "ATSH7E0" else "ATSH7DF", 1500) } catch (_: ElmTimeoutException) {}
        try { send("ATCRA", 1500) } catch (_: ElmTimeoutException) {}
        try { send("ATH0", 1500) } catch (_: ElmTimeoutException) {}
    }

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
        var r = probe(8000)
        if (r != null) {
            send("ATSP0")
            r = probe(15000)
        }
        if (r != null) {
            // Шина отвечает, но не на общий запрос — пробуем адресно к блоку двигателя.
            send("ATSH7E0")
            r = probe(4000)
            if (r != null) {
                send("ATSH7DF")
                throw IOException("ЭБУ не отвечает на запросы данных. Включено ли зажигание? Ответ: ${r.ifBlank { "пусто" }}")
            }
            addressed = true
            log("Блок двигателя отвечает только на адресные запросы (7E0)")
        }
        log("Протокол данных: $protocolName")
        val dp = try { send("ATDPN") } catch (_: ElmTimeoutException) { "" }
        val proto = dp.trim().trimStart('A')
        isCan = proto in setOf("6", "7", "8", "9")
    }

    /**
     * Пробует стандартный OBD2 (01 00), затем японский Toyota JOBD (21 00).
     * Возвращает null при успехе, иначе последний ответ блока для сообщения об ошибке.
     */
    private suspend fun probe(timeoutMs: Long): String? {
        val r1 = try { send("0100", timeoutMs) } catch (_: ElmTimeoutException) { "" }
        if (r1.replace(" ", "").contains("4100")) {
            pidMode = 0x01
            return null
        }
        val r2 = try { send("2100", 4000) } catch (_: ElmTimeoutException) { "" }
        if (r2.replace(" ", "").contains("6100")) {
            pidMode = 0x21
            return null
        }
        return r2.ifBlank { r1 }
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
                send(String.format(Locale.US, "%02X%02X", pidMode, base), 4000)
            } catch (_: ElmTimeoutException) {
                break
            }
            val d = Pids.extract(r, pidMode, base) ?: break
            for (i in 0 until 32) {
                val byte = d.getOrNull(i / 8) ?: break
                if (byte and (0x80 shr (i % 8)) != 0) set.add(base + i + 1)
            }
        }
        return set
    }

    suspend fun readPid(p: PidDef): Double? {
        val r = send(String.format(Locale.US, "%02X%02X", pidMode, p.pid), 1500)
        val d = Pids.extract(r, pidMode, p.pid) ?: return null
        return p.decode(d)
    }

    suspend fun adapterVoltage(): Double? {
        val r = send("ATRV", 1500)
        return Regex("([0-9]+(?:\\.[0-9]+)?)").find(r)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    suspend fun readDtcs(mode: Int): List<String> {
        val r = send(String.format(Locale.US, "%02X", mode), 6000)
        val c = r.replace(" ", "")
        if (c.contains("NODATA")) return emptyList()
        if (c.contains(String.format(Locale.US, "7F%02X", mode))) return emptyList()
        return Dtc.parse(r, mode + 0x40, isCan)
    }

    /** Сохранённые коды: OBD2 сервис 03, при отказе — KWP/JOBD сервис 13. */
    suspend fun readStoredDtcs(): List<String> {
        val r = send("03", 6000)
        val c = r.replace(" ", "")
        if (c.contains("43")) return Dtc.parse(r, 0x43, isCan)
        if (c.contains("NODATA")) return emptyList()
        val r2 = send("13", 6000)
        val c2 = r2.replace(" ", "")
        if (c2.contains("53")) return Dtc.parse(r2, 0x53, true)
        return emptyList()
    }

    suspend fun clearDtcs(): Boolean {
        val r = send("04", 8000).replace(" ", "")
        if (r.contains("44")) return true
        // KWP/JOBD: очистка всех групп кодов
        val r2 = (try { send("14FF00", 8000) } catch (_: ElmTimeoutException) { "" }).replace(" ", "")
        if (r2.contains("54")) return true
        val r3 = (try { send("14", 8000) } catch (_: ElmTimeoutException) { "" }).replace(" ", "")
        return r3.contains("54")
    }

    /**
     * Пропуски зажигания по цилиндрам из результатов бортовых тестов (Mode 06, MID A2–A7).
     * Возвращает номер цилиндра → число пропусков; null — блок эти данные не отдаёт.
     */
    suspend fun readMisfires(): Map<Int, Int>? {
        val out = LinkedHashMap<Int, Int>()
        for (cyl in 1..6) {
            val mid = 0xA1 + cyl
            val r = try {
                send(String.format(Locale.US, "06%02X", mid), 3000)
            } catch (_: ElmTimeoutException) {
                continue
            }
            val hex = joinFrames(r)
            val marker = String.format(Locale.US, "46%02X", mid)
            val idx = hex.indexOf(marker)
            if (idx < 0 || idx % 2 != 0) continue
            // записи по 9 байт: MID, TID, UASID, значение(2), мин(2), макс(2)
            val bytes = Pids.hexBytes(hex.substring(idx + 2))
            var current: Int? = null
            var average: Int? = null
            var i = 0
            while (i + 8 < bytes.size) {
                if (bytes[i] != mid) break
                val tid = bytes[i + 1]
                val v = bytes[i + 3] * 256 + bytes[i + 4]
                if (tid == 0x0C) current = v
                if (tid == 0x0B) average = v
                i += 9
            }
            (current ?: average)?.let { out[cyl] = it }
        }
        return out.ifEmpty { null }
    }

    /**
     * Запросы Mode 21 к другому блоку (например, АКПП 7E1). Возвращает байты данных после «61 xx»
     * для каждого ответившего запроса и восстанавливает рабочий заголовок.
     */
    suspend fun queryModule(header: String, cmds: List<String>): Map<String, List<Int>> {
        val out = HashMap<String, List<Int>>()
        try {
            send("ATSH$header", 1000)
            for (cmd in cmds) {
                val r = try { send(cmd, 1500) } catch (_: ElmTimeoutException) { continue }
                val hex = joinFrames(r)
                val marker = "6" + cmd.substring(1)
                var idx = hex.indexOf(marker)
                while (idx >= 0 && idx % 2 != 0) idx = hex.indexOf(marker, idx + 1)
                if (idx < 0) continue
                out[cmd] = Pids.hexBytes(hex.substring(idx + marker.length)).toList()
            }
        } finally {
            try { send(if (addressed) "ATSH7E0" else "ATSH7DF", 1000) } catch (_: ElmTimeoutException) {}
        }
        return out
    }

    /** Склеивает многокадровый ответ CAN в одну hex-строку без номеров кадров. */
    private fun joinFrames(r: String): String {
        val sb = StringBuilder()
        for (raw in r.lines()) {
            val l = raw.replace(" ", "").trim().uppercase(Locale.US)
            if (l.isEmpty() || l.length == 3 && l.all { it.isLetterOrDigit() } && !l.contains(':')) continue
            sb.append(l.substringAfter(':'))
        }
        return sb.toString()
    }

    suspend fun readFreezeFrame(): FreezeFrame {
        val fm = freezeMode
        val r = send(String.format(Locale.US, "%02X0200", fm), 3000)
        val d = Pids.extract(r, fm, 0x02, "00")
        val dtc = if (d != null && d.size >= 2 && (d[0] != 0 || d[1] != 0)) Dtc.decode(d[0], d[1]) else null
        if (dtc == null) return FreezeFrame(null, emptyList())
        val items = ArrayList<Pair<String, String>>()
        for (p in Pids.ALL) {
            if (p.pid !in Pids.FREEZE_PIDS) continue
            val rr = try {
                send(String.format(Locale.US, "%02X%02X00", fm, p.pid), 1500)
            } catch (_: ElmTimeoutException) {
                continue
            }
            val dd = Pids.extract(rr, fm, p.pid, "00") ?: continue
            val v = p.decode(dd) ?: continue
            items.add(p.title to "${Pids.format(p, v)} ${p.unit}")
        }
        return FreezeFrame(dtc, items)
    }
}
