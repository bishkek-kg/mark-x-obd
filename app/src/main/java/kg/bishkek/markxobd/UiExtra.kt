package kg.bishkek.markxobd

import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun n0(v: Double?) = v?.let { String.format(Locale.US, "%.0f", it) } ?: "—"
private fun n1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
private fun n2(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

// ---------------- Новые ошибки ----------------

@Composable
fun NewDtcBanner(vm: ObdSession) {
    val fresh by vm.newDtc.collectAsState()
    if (fresh.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Новая ошибка с прошлого подключения: ${fresh.joinToString(" ")}. Подробности — на вкладке «Ошибки».",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.dismissNewDtc() }) { Text("OK") }
        }
    }
}

// ---------------- Текущая поездка ----------------

@Composable
fun LiveTripLine(vm: ObdSession, color: Color = Color.Unspecified, big: Boolean = false) {
    val trip by vm.liveTrip.collectAsState()
    vm.historyTick.collectAsState().value // обновлять вместе с опросом
    val t = trip ?: return
    val min = (System.currentTimeMillis() - t.startedAt) / 60_000.0
    val parts = mutableListOf("${n0(min)} мин", "${n1(t.distKm)} км")
    if (t.fuelL > 0.005) parts.add("${n2(t.fuelL)} л")
    t.avgL100?.let { parts.add("${n1(it)} л/100") }
    t.cost?.let { parts.add("${n0(it)} сом") }
    Text("Поездка: " + parts.joinToString(" · "), color = color, fontSize = if (big) 18.sp else 13.sp)
}

// ---------------- Пороги ----------------

@Composable
fun ThresholdsCard(vm: ObdSession) {
    val th by vm.thresholds.collectAsState()
    var open by remember { mutableStateOf(false) }
    var overheat by remember(th) { mutableStateOf(n0(th.overheat)) }
    var warn by remember(th) { mutableStateOf(n0(th.warn)) }
    var lowV by remember(th) { mutableStateOf(n1(th.lowV)) }
    var rpm by remember(th) { mutableStateOf(if (th.rpmMax > 0) n0(th.rpmMax) else "") }
    var price by remember(th) { mutableStateOf(if (th.fuelPrice > 0) n1(th.fuelPrice) else "") }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Пороги и цена топлива", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Перегрев ${n0(th.overheat)} °C · предупреждение ${n0(th.warn)} °C · зарядка ниже ${n1(th.lowV)} В · " +
                            (if (th.rpmMax > 0) "обороты ${n0(th.rpmMax)}" else "обороты не следим") +
                            (if (th.fuelPrice > 0) " · бензин ${n1(th.fuelPrice)} сом/л" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { open = !open }) { Text(if (open) "Скрыть" else "Изменить") }
            }
            if (open) {
                @Composable
                fun field(label: String, value: String, onChange: (String) -> Unit) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                field("Перегрев, °C (тревога каждые 30 с)", overheat) { overheat = it }
                field("Предупреждение, °C (один раз)", warn) { warn = it }
                field("Генератор не заряжает ниже, В", lowV) { lowV = it }
                field("Предупреждать об оборотах выше (пусто — нет)", rpm) { rpm = it }
                field("Цена бензина, сом/л (для стоимости поездки)", price) { price = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.setThresholds(
                            ThresholdValues(
                                overheat = num(overheat) ?: th.overheat,
                                warn = num(warn) ?: th.warn,
                                lowV = num(lowV) ?: th.lowV,
                                rpmMax = num(rpm) ?: 0.0,
                                fuelPrice = num(price) ?: 0.0,
                            ),
                        )
                        open = false
                    }) { Text("Сохранить") }
                    OutlinedButton(onClick = {
                        vm.setThresholds(ThresholdValues(fuelPrice = th.fuelPrice))
                        open = false
                    }) { Text("По умолчанию") }
                }
            }
        }
    }
}

// ---------------- Разгон ----------------

@Composable
fun AccelCard(vm: ObdSession, connected: Boolean) {
    val st by vm.accel.collectAsState()
    val hist by vm.accelHistory.collectAsState()
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Разгон 0–60 и 0–100 км/ч", fontWeight = FontWeight.SemiBold)
            Text(
                "Только на пустой прямой дороге, где это разрешено и безопасно. Нажмите «Приготовиться», " +
                    "остановитесь и разгоняйтесь — на экран смотреть не нужно, результат скажет голос. " +
                    "Точность ±0,3 с: скорость приходит из блока несколько раз в секунду.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            val active = st != null && st!!.phase != AccelPhase.DONE
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startAccel() }, enabled = connected && !active) { Text("Приготовиться") }
                OutlinedButton(onClick = { vm.cancelAccel() }, enabled = active) { Text("Отмена") }
            }
            st?.let { s ->
                Spacer(Modifier.height(6.dp))
                Text(s.message, fontWeight = FontWeight.Medium)
                Text(
                    "Скорость ${n0(s.speed)} км/ч" + (s.elapsed?.let { " · ${n1(it)} с" } ?: "") +
                        " · 0–60: ${secs(s.t60)} · 0–100: ${secs(s.t100)}",
                    fontSize = 14.sp,
                )
            }
            if (hist.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val best60 = hist.mapNotNull { it.t60 }.minOrNull()
                val best100 = hist.mapNotNull { it.t100 }.minOrNull()
                Text("Лучшее: 0–60 ${secs(best60)} · 0–100 ${secs(best100)}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                hist.take(5).forEach { r ->
                    Text("${fmt.format(Date(r.time))} — 0–60 ${secs(r.t60)}, 0–100 ${secs(r.t100)}", fontSize = 13.sp)
                }
                TextButton(onClick = { vm.clearAccelHistory() }) { Text("Очистить историю") }
            }
        }
    }
}

// ---------------- Пропуски зажигания ----------------

@Composable
fun MisfireCard(vm: ObdSession, connected: Boolean) {
    val res by vm.misfires.collectAsState()
    val busy by vm.misfireBusy.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Пропуски зажигания по цилиндрам", fontWeight = FontWeight.SemiBold)
            Text(
                "Читает счётчики пропусков из результатов бортовых тестов (Mode 06). Лучше на прогретом двигателе на холостых " +
                    "после поездки. Если блок их не отдаёт — будет сказано.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.readMisfires() }, enabled = connected && !busy) { Text(if (busy) "Читаю…" else "Прочитать") }
            res?.let { r ->
                Spacer(Modifier.height(6.dp))
                r.counts?.let { c ->
                    val max = maxOf(1, c.values.maxOrNull() ?: 1)
                    // 4GR-FSE: цилиндры 1-3-5 — правый ряд, 2-4-6 — левый
                    c.forEach { (cyl, n) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Цил. $cyl", fontSize = 14.sp, modifier = Modifier.width(64.dp))
                            Surface(
                                color = if (n == 0) Color(0xFF66BB6A) else if (n == max) Color(0xFFE53935) else Color(0xFFFFB300),
                                modifier = Modifier.height(14.dp).width((8 + 180 * n / max).dp),
                            ) {}
                            Spacer(Modifier.width(6.dp))
                            Text("$n", fontSize = 14.sp)
                        }
                    }
                }
                Text(r.message, fontSize = 14.sp)
            }
        }
    }
}

// ---------------- Тренды по поездкам ----------------

private data class TrendPoint(val name: String, val s: TripStats)

@Composable
fun TrendsCard(files: List<File>, onClose: () -> Unit) {
    val points by produceState<List<TrendPoint>?>(null, files) {
        value = withContext(Dispatchers.IO) {
            files.filter { it.name.endsWith(".csv") }
                .sortedBy { it.name }
                .takeLast(40)
                .mapNotNull { f ->
                    val d = TripData.load(f) ?: return@mapNotNull null
                    // совсем короткие записи (заводил-глушил) только шумят
                    if (d.durationMin < 3) return@mapNotNull null
                    TrendPoint(f.nameWithoutExtension.removePrefix("trip_"), TripReport.stats(d))
                }
        }
    }
    val c1 = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тренды по поездкам", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            val p = points
            if (p == null) {
                Text("Считаю…")
                return@Column
            }
            if (p.size < 2) {
                Text("Нужно хотя бы две поездки длиннее 3 минут")
                return@Column
            }
            Text(
                "Поездок: ${p.size}, с ${p.first().name.take(10)} по ${p.last().name.take(10)}. По горизонтали — номер поездки.",
                style = MaterialTheme.typography.bodySmall,
            )
            @Composable
            fun chart(title: String, sel: (TripStats) -> Double?, danger: Float? = null, low: Float? = null) {
                val pts = p.mapIndexedNotNull { i, tp -> sel(tp.s)?.let { (i + 1).toFloat() to it.toFloat() } }
                if (pts.size < 2) return
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.labelMedium)
                MultiChart(listOf(pts to c1), danger = danger, low = low, xUnit = "№", connectDots = true)
            }
            chart("Макс. температура ОЖ, °C", { it.maxCoolant }, danger = Thresholds.overheat.toFloat())
            chart("Мин. напряжение на ходу, В", { it.minV }, low = Thresholds.lowV.toFloat())
            chart("Средний расход, л/100 км", { it.avgL100 })
            chart("Холостые на прогретом, об/мин", { it.warmIdleRpm })
            chart("Стиль вождения, баллы", { s -> if (s.distKm >= 1) s.styleScore.toDouble() else null })
            val total = p.sumOf { it.s.distKm }
            val fuel = p.sumOf { it.s.fuelL }
            Spacer(Modifier.height(6.dp))
            Text(
                "Всего ${n0(total)} км, топлива ≈ ${n1(fuel)} л" +
                    (if (total > 5) ", в среднем ≈ ${n1(fuel / total * 100)} л/100 км" else "") +
                    (if (Thresholds.fuelPrice > 0) ", ≈ ${n0(fuel * Thresholds.fuelPrice)} сом" else ""),
                fontSize = 14.sp,
            )
        }
    }
}

// ---------------- Карта поездки ----------------

private fun tempColor(c: Double?): Int = when {
    c == null -> android.graphics.Color.GRAY
    c >= Thresholds.overheat -> android.graphics.Color.rgb(229, 57, 53)
    c >= 95 -> android.graphics.Color.rgb(255, 179, 0)
    else -> android.graphics.Color.rgb(46, 160, 67)
}

@Composable
fun TripMapDialog(file: File, onClose: () -> Unit) {
    val data = remember(file.absolutePath) { TripData.load(file) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Карта поездки ${file.nameWithoutExtension.removePrefix("trip_")}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Цвет — температура ОЖ: зелёный < 95 °C, жёлтый 95–${n0(Thresholds.overheat - 1)}, красный ≥ ${n0(Thresholds.overheat)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                val lat = data?.col("lat")
                val lon = data?.col("lon")
                val cool = data?.col("coolant")
                val idx = if (data == null || lat == null || lon == null) emptyList()
                else data.t.indices.filter { lat[it] != null && lon[it] != null }
                if (data == null || idx.size < 2) {
                    Text(
                        "В этой записи нет координат. Включите «Записывать GPS» на вкладке «Связь» — следующие поездки будут с картой.",
                        modifier = Modifier.padding(16.dp),
                    )
                    return@Column
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        val cfg = Configuration.getInstance()
                        cfg.load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                        cfg.userAgentValue = ctx.packageName
                        cfg.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                        cfg.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
                        val map = MapView(ctx)
                        map.setTileSource(TileSourceFactory.MAPNIK)
                        map.setMultiTouchControls(true)
                        val all = ArrayList<GeoPoint>()
                        var seg = ArrayList<GeoPoint>()
                        var segColor = 0
                        var lastC: Double? = null
                        fun flush() {
                            if (seg.size >= 2) {
                                val line = Polyline(map)
                                line.setPoints(seg)
                                line.outlinePaint.color = segColor
                                line.outlinePaint.strokeWidth = 12f
                                line.outlinePaint.strokeCap = Paint.Cap.ROUND
                                map.overlays.add(line)
                            }
                        }
                        for (i in idx) {
                            val g = GeoPoint(lat!![i]!!, lon!![i]!!)
                            all.add(g)
                            lastC = cool?.get(i) ?: lastC
                            val col = tempColor(lastC)
                            if (seg.isEmpty()) segColor = col
                            if (col != segColor) {
                                seg.add(g)
                                flush()
                                seg = arrayListOf(g)
                                segColor = col
                            } else {
                                seg.add(g)
                            }
                        }
                        flush()
                        // метки — на ближайшую точку с координатами
                        for (m in data.marks) {
                            val ts = m.minute * 60
                            val i = idx.minByOrNull { kotlin.math.abs(data.t[it] - ts) } ?: continue
                            if (kotlin.math.abs(data.t[i] - ts) > 30) continue
                            val mk = Marker(map)
                            mk.position = GeoPoint(lat!![i]!!, lon!![i]!!)
                            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            mk.title = "${m.clock} — ${m.label}" + (m.coolant?.let { " (ОЖ ${n0(it)} °C)" } ?: "")
                            map.overlays.add(mk)
                        }
                        listOf(all.first() to "Старт", all.last() to "Финиш").forEach { (g, t) ->
                            val mk = Marker(map)
                            mk.position = g
                            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            mk.title = t
                            map.overlays.add(mk)
                        }
                        map.addOnFirstLayoutListener { _, _, _, _, _ ->
                            try {
                                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), false, 80)
                            } catch (_: Exception) {
                                map.controller.setZoom(14.0)
                                map.controller.setCenter(all.first())
                            }
                        }
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(all.first())
                        map
                    },
                    onRelease = { it.onDetach() },
                )
                Text(
                    "Подложка © участники OpenStreetMap",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ---------------- Топливо ----------------

@Composable
fun FuelLine(vm: ObdSession, color: Color = Color.Unspecified, big: Boolean = false) {
    val t by vm.fuelTank.collectAsState()
    if (!t.known) return
    Text(
        "Бак ≈ ${n0(t.levelL)} л · запас ≈ ${n0(t.rangeKm)} км",
        color = if (t.levelL < 8) Color(0xFFFFB300) else color,
        fontSize = if (big) 18.sp else 13.sp,
    )
}

@Composable
fun FuelCard(vm: ObdSession) {
    val t by vm.fuelTank.collectAsState()
    var show by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (t.known) {
                    Text("Топливо ≈ ${n0(t.levelL)} из ${n0(t.tankL)} л · запас ≈ ${n0(t.rangeKm)} км", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        "С заправки ${n0(t.sinceKm)} км, ≈ ${n1(t.sinceRawL * t.factor)} л" +
                            (t.lastRealL100?.let { " · реальный расход ${n1(it)} л/100" } ?: "") +
                            " · оценка",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Учёт топлива", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("Заправьтесь до полного и отметьте — дальше остаток считается сам", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { show = true }) { Text("Заправка") }
        }
    }
    if (show) RefuelDialog(vm, t) { show = false }
}

@Composable
private fun RefuelDialog(vm: ObdSession, t: FuelTank, onClose: () -> Unit) {
    var liters by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    var tank by remember { mutableStateOf(n0(t.tankL)) }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Заправка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = liters, onValueChange = { liters = it }, singleLine = true,
                    label = { Text("Сколько залили, л") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = full, onCheckedChange = { full = it })
                    Text("До полного бака (до щелчка пистолета)")
                }
                Text(
                    "Две заправки до полного подряд уточняют расчёт: приложение сравнит свой счёт с реально залитыми литрами.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tank, onValueChange = { tank = it }, singleLine = true,
                    label = { Text("Объём бака, л (Mark X — 71)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = num(liters) != null || full,
                onClick = {
                    num(tank)?.let { if (it != t.tankL) vm.setTankSize(it) }
                    vm.refuel(num(liters) ?: 0.0, full)
                    onClose()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}


// ---------------- Быстрый запуск замера разгона ----------------

@Composable
fun AccelQuickButton(vm: ObdSession) {
    val st by vm.accel.collectAsState()
    val conn by vm.connState.collectAsState()
    val active = st != null && st!!.phase != AccelPhase.DONE
    OutlinedButton(
        onClick = { if (active) vm.cancelAccel() else vm.startAccel() },
        enabled = conn == ConnState.CONNECTED,
    ) { Text(if (active) "Отмена разгона" else "0–100") }
}

@Composable
fun AccelLine(vm: ObdSession, color: Color = Color.Unspecified) {
    val st by vm.accel.collectAsState()
    val s = st ?: return
    val tail = when (s.phase) {
        AccelPhase.RUNNING -> " · ${n1(s.elapsed)} с"
        AccelPhase.DONE -> " · 0–60 ${secs(s.t60)} · 0–100 ${secs(s.t100)}"
        else -> ""
    }
    Text("Разгон: ${s.message}$tail", color = color, fontSize = 18.sp, fontWeight = FontWeight.Medium)
}
