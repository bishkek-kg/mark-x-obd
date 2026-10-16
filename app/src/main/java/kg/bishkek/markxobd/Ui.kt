package kg.bishkek.markxobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Тема ----------------

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF7FB8FF), secondary = Color(0xFFFFC94A))
    } else {
        lightColorScheme(primary = Color(0xFF0F5BB5), secondary = Color(0xFFB07A00))
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

private enum class Tab(val title: String) {
    CONNECT("Связь"), DASH("Датчики"), CHARTS("Графики"), TESTS("Тесты"), DTC("Ошибки"), LOGS("Записи")
}

// ---------------- Корень ----------------

@Composable
fun App() {
    val ctx = LocalContext.current
    val vm = remember { ObdSession.get(ctx.applicationContext as Application) }
    val required = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var granted by remember {
        mutableStateOf(required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED })
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
        if (granted) {
            vm.refreshPaired()
            vm.autoConnectOnStart()
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            permLauncher.launch(required)
        } else {
            vm.refreshPaired()
            vm.autoConnectOnStart()
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.CONNECT) }
    val connState by vm.connState.collectAsState()
    LaunchedEffect(connState) {
        if (connState == ConnState.CONNECTED && tab == Tab.CONNECT) tab = Tab.DASH
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.CONNECT -> Icons.Filled.Settings
                                    Tab.DASH -> Icons.Filled.Home
                                    Tab.CHARTS -> Icons.Filled.DateRange
                                    Tab.TESTS -> Icons.Filled.Build
                                    Tab.DTC -> Icons.Filled.Warning
                                    Tab.LOGS -> Icons.Filled.List
                                },
                                contentDescription = t.title,
                            )
                        },
                        label = { Text(t.title, fontSize = 10.sp, maxLines = 1) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TopStatus(vm)
            if (!granted) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Нужно разрешение на Bluetooth, чтобы подключиться к адаптеру.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permLauncher.launch(required) }) { Text("Разрешить") }
                    }
                }
            }
            when (tab) {
                Tab.CONNECT -> ConnectScreen(vm)
                Tab.DASH -> DashboardScreen(vm)
                Tab.CHARTS -> ChartsScreen(vm)
                Tab.TESTS -> TestsScreen(vm)
                Tab.DTC -> DtcScreen(vm)
                Tab.LOGS -> LogsScreen(vm)
            }
        }
    }
}

@Composable
private fun TopStatus(vm: ObdSession) {
    val status by vm.status.collectAsState()
    val overheat by vm.overheat.collectAsState()
    val values by vm.values.collectAsState()
    val rec by vm.recordingFile.collectAsState()
    if (overheat) {
        Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
            Text(
                "ПЕРЕГРЕВ: ОЖ ${values["coolant"]?.let { String.format(Locale.US, "%.0f", it) } ?: "?"} °C — остановитесь и заглушите двигатель",
                color = MaterialTheme.colorScheme.onError,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (rec != null) {
            Text("● запись", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

// ---------------- Подключение ----------------

@SuppressLint("MissingPermission")
@Composable
private fun ConnectScreen(vm: ObdSession) {
    val connState by vm.connState.collectAsState()
    val paired by vm.pairedDevices.collectAsState()
    val ble by vm.bleDevices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val console by vm.console.collectAsState()
    var showConsole by rememberSaveable { mutableStateOf(false) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshPaired()
    }
    val btOn = vm.adapter?.isEnabled == true
    val last = vm.lastDevice
    val autoReconnect by vm.autoReconnect.collectAsState()
    val autoRecord by vm.autoRecord.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!btOn) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("Bluetooth выключен")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            try {
                                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            } catch (_: Exception) {
                            }
                        }) { Text("Включить") }
                    }
                }
            }
        }
        item {
            when (connState) {
                ConnState.CONNECTED, ConnState.CONNECTING, ConnState.WAITING -> Button(
                    onClick = { vm.disconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (connState == ConnState.CONNECTED) "Отключиться" else "Отменить подключение") }

                ConnState.DISCONNECTED -> {
                    if (last != null && !vm.sessionActive) {
                        Button(onClick = { vm.connect(last) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Подключиться к ${last.name}")
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SwitchRow("Автоподключение", "При запуске приложения и при обрыве связи", autoReconnect) {
                        vm.setAutoReconnect(it)
                    }
                    SwitchRow("Автозапись поездки", "Начинать запись сразу после подключения", autoRecord) {
                        vm.setAutoRecord(it)
                    }
                }
            }
        }
        item {
            Text(
                "Сначала спарьте адаптер в настройках Bluetooth телефона (обычно называется V-LINK, PIN 1234 или 0000). " +
                    "Зажигание должно быть включено.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сопряжённые устройства", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshPaired() }) { Text("Обновить") }
            }
        }
        if (paired.isEmpty()) {
            item { Text("Нет сопряжённых устройств", style = MaterialTheme.typography.bodySmall) }
        }
        items(paired, key = { "c" + it.address }) { d ->
            DeviceRow(d, enabled = connState == ConnState.DISCONNECTED) { vm.connect(d) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bluetooth LE (если адаптер BLE)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (scanning) vm.stopBleScan() else vm.startBleScan() }) {
                    Text(if (scanning) "Стоп" else "Поиск")
                }
            }
        }
        items(ble, key = { "b" + it.address }) { d ->
            DeviceRow(d, enabled = connState == ConnState.DISCONNECTED) { vm.connect(d) }
        }
        item {
            TextButton(onClick = { showConsole = !showConsole }) {
                Text(if (showConsole) "Скрыть журнал обмена" else "Показать журнал обмена с адаптером")
            }
        }
        if (showConsole) {
            items(console.takeLast(60)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DeviceRow(d: DeviceItem, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = FontWeight.Medium)
                Text(d.address + if (d.ble) " · BLE" else "", style = MaterialTheme.typography.bodySmall)
            }
            Text("Подключить", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------------- Датчики ----------------

@Composable
private fun DashboardScreen(vm: ObdSession) {
    val values by vm.values.collectAsState()
    val supported by vm.supportedKeys.collectAsState()
    val charts by vm.chartKeys.collectAsState()
    val rec by vm.recordingFile.collectAsState()
    val connState by vm.connState.collectAsState()
    val defs = if (supported.isEmpty()) Pids.DISPLAY else Pids.DISPLAY.filter { it.key in supported }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Нажмите на плитку, чтобы добавить или убрать её из графиков",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            RecordButton(rec != null, connState == ConnState.CONNECTED) { vm.toggleRecording() }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(defs, key = { it.key }) { p ->
                val v = values[p.key]
                val container = when {
                    p.key == "coolant" && v != null && v >= Pids.OVERHEAT_C -> MaterialTheme.colorScheme.errorContainer
                    p.key == "coolant" && v != null && v >= 100.0 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Card(
                    Modifier.fillMaxWidth().clickable { vm.toggleChart(p.key) },
                    colors = CardDefaults.cardColors(containerColor = container),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(p.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (p.key in charts) Text("📈", fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                v?.let { Pids.format(p, it) } ?: "—",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(p.unit, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (recording) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Стоп записи") }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text("● Запись") }
    }
}

// ---------------- Графики ----------------

@Composable
private fun ChartsScreen(vm: ObdSession) {
    val charts by vm.chartKeys.collectAsState()
    val tick by vm.historyTick.collectAsState()
    val values by vm.values.collectAsState()
    val fanEvents by vm.fanEvents.collectAsState()
    var windowMin by rememberSaveable { mutableStateOf(5) }
    val end = if (tick == 0L) System.currentTimeMillis() else tick
    val start = end - windowMin * 60_000L
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(2, 5, 10, 30).forEach { m ->
                    if (m == windowMin) {
                        Button(onClick = {}, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("$m мин") }
                    } else {
                        OutlinedButton(onClick = { windowMin = m }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("$m мин") }
                    }
                }
            }
        }
        if (charts.isEmpty()) {
            item { Text("Графиков нет. Добавьте их на вкладке «Датчики» нажатием на плитку.") }
        }
        items(charts, key = { it }) { key ->
            val p = Pids.byKey(key) ?: return@items
            val pts = remember(tick, windowMin, key) { vm.history.get(key, start) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            (values[key]?.let { Pids.format(p, it) } ?: "—") + " " + p.unit,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { vm.toggleChart(key) }) { Text("✕") }
                    }
                    LineChart(
                        points = pts,
                        startMs = start,
                        endMs = end,
                        windowMin = windowMin,
                        markers = if (key == "coolant" || key == "ecuV" || key == Pids.ADAPTER_V) fanEvents.map { it.time } else emptyList(),
                        dangerLevel = if (key == "coolant") Pids.OVERHEAT_C.toFloat() else null,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Вероятные включения вентиляторов", fontWeight = FontWeight.Medium)
                    Text(
                        "Определяются по кратковременной просадке напряжения на работающем двигателе. Это ориентир — сверяйте на слух.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (fanEvents.isEmpty()) {
                        Text("Пока не обнаружено", style = MaterialTheme.typography.bodySmall)
                    }
                    fanEvents.take(15).forEach { ev ->
                        Text(
                            "${timeFmt.format(Date(ev.time))} — ОЖ ${ev.coolant?.let { String.format(Locale.US, "%.0f °C", it) } ?: "?"}, " +
                                "просадка ${String.format(Locale.US, "%.2f", ev.drop)} В",
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        item {
            TextButton(onClick = { vm.clearHistory() }) { Text("Очистить графики") }
        }
    }
}

@Composable
fun LineChart(
    points: List<Pair<Long, Float>>,
    startMs: Long,
    endMs: Long,
    windowMin: Int,
    markers: List<Long>,
    dangerLevel: Float?,
    leftLabel: String? = null,
    rightLabel: String = "сейчас",
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.secondary
    val dangerColor = MaterialTheme.colorScheme.error
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textPx = with(LocalDensity.current) { 11.sp.toPx() }

    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val paint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = textPx
            isAntiAlias = true
        }
        val left = 40.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val native = drawContext.canvas.nativeCanvas

        if (points.isEmpty()) {
            native.drawText("Нет данных", left, size.height / 2, paint)
            return@Canvas
        }
        var minV = points.minOf { it.second }
        var maxV = points.maxOf { it.second }
        if (dangerLevel != null && maxV > dangerLevel - 15f) maxV = maxOf(maxV, dangerLevel)
        if (maxV - minV < 1f) {
            val c = (maxV + minV) / 2f
            minV = c - 0.5f
            maxV = c + 0.5f
        }
        val pad = (maxV - minV) * 0.1f
        minV -= pad
        maxV += pad
        val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = left + (right - left) * ((t - startMs).toFloat() / span)
        fun y(v: Float) = bottom - (bottom - top) * ((v - minV) / (maxV - minV))
        val decimals = if (maxV - minV < 10f) 1 else 0

        for (i in 0..4) {
            val v = minV + (maxV - minV) * i / 4f
            val yy = y(v)
            drawLine(gridColor, Offset(left, yy), Offset(right, yy), 1f)
            native.drawText(String.format(Locale.US, "%.${decimals}f", v), 0f, yy + textPx / 3f, paint)
        }
        if (dangerLevel != null && dangerLevel in minV..maxV) {
            val yy = y(dangerLevel)
            drawLine(dangerColor, Offset(left, yy), Offset(right, yy), 2f)
        }
        for (m in markers) {
            if (m < startMs) continue
            val xx = x(m)
            drawLine(markerColor, Offset(xx, top), Offset(xx, bottom), 2f)
        }
        val path = Path()
        var first = true
        for ((t, v) in points) {
            val px = x(t)
            val py = y(v)
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

        native.drawText(leftLabel ?: "−$windowMin мин", left, size.height - 2f, paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        native.drawText(rightLabel, right, size.height - 2f, paint)
    }
}

// ---------------- Ошибки ----------------

@Composable
private fun DtcScreen(vm: ObdSession) {
    val result by vm.dtcResult.collectAsState()
    val freeze by vm.freezeFrame.collectAsState()
    val busy by vm.dtcBusy.collectAsState()
    val msg by vm.dtcMessage.collectAsState()
    val connState by vm.connState.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val connected = connState == ConnState.CONNECTED

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.readDtc() }, enabled = connected && !busy) { Text("Прочитать") }
                OutlinedButton(onClick = { vm.readFreeze() }, enabled = connected && !busy) { Text("Стоп-кадр") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = connected && !busy) { Text("Стереть") }
            }
        }
        if (busy) item { Text("Обмен с блоком…") }
        msg?.let { m -> item { Text(m, fontWeight = FontWeight.Medium) } }
        result?.let { r ->
            item { DtcSection("Подтверждённые (сохранённые)", r.stored) }
            item { DtcSection("Ожидающие (временные)", r.pending) }
            if (r.permanent.isNotEmpty()) item { DtcSection("Постоянные", r.permanent) }
        }
        freeze?.let { ff ->
            if (ff.dtc != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Стоп-кадр по ошибке ${ff.dtc}", fontWeight = FontWeight.Medium)
                            Text(Dtc.describe(ff.dtc), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            ff.items.forEach { (k, v) ->
                                Row {
                                    Text(k, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                    Text(v, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Перед стиранием сохраните список ошибок (скриншот) — он пригодится мастеру.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Стереть ошибки?") },
            text = { Text("Коды и стоп-кадр будут удалены из блока. Двигатель должен быть заглушён, зажигание включено.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearDtc()
                }) { Text("Стереть") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DtcSection(title: String, codes: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("$title: ${codes.size}", fontWeight = FontWeight.Medium)
            if (codes.isEmpty()) {
                Text("нет", style = MaterialTheme.typography.bodySmall)
            }
            codes.forEach { c ->
                Spacer(Modifier.height(6.dp))
                Text(c, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(Dtc.describe(c), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------- Записи ----------------

@Composable
private fun LogsScreen(vm: ObdSession) {
    val files by vm.logFiles.collectAsState()
    val rec by vm.recordingFile.collectAsState()
    val connState by vm.connState.collectAsState()
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshLogs() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Записи поездок (CSV) и журналы исследований (TXT). Их можно отправить в Telegram или в чат с Claude для разбора.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                RecordButton(rec != null, connState == ConnState.CONNECTED) { vm.toggleRecording() }
            }
        }
        if (files.isEmpty()) item { Text("Записей пока нет") }
        items(files, key = { it.absolutePath }) { f ->
            val isCsv = f.name.endsWith(".csv")
            val recording = f == rec
            val open = expanded == f.absolutePath
            val summary = remember(f.absolutePath, f.lastModified(), open) {
                if (open && isCsv) TripReport.fromCsv(f)?.text else null
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        (if (recording) "идёт запись · " else "") + "${f.length() / 1024} КБ",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        if (isCsv && !recording) {
                            TextButton(onClick = { expanded = if (open) null else f.absolutePath }) {
                                Text(if (open) "Скрыть сводку" else "Сводка")
                            }
                        }
                        if (!recording) {
                            TextButton(onClick = { saveToDownloads(ctx, f) }) { Text("Скачать") }
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            val text = if (isCsv && !recording) TripReport.fromCsv(f)?.text else null
                            shareFile(ctx, f, text)
                        }) { Text("Отправить") }
                        if (!recording) {
                            TextButton(onClick = { vm.deleteLog(f) }) { Text("Удалить") }
                        }
                    }
                    if (summary != null) {
                        Text(summary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Копирует файл в «Загрузки/MarkXOBD», чтобы его можно было прикрепить в любом приложении. */
private fun saveToDownloads(ctx: android.content.Context, f: File) {
    val ok = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(
                    android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    if (f.name.endsWith(".csv")) "text/csv" else "text/plain",
                )
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/MarkXOBD",
                )
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("не удалось создать файл")
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("не удалось записать файл")
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "MarkXOBD",
            )
            dir.mkdirs()
            f.copyTo(File(dir, f.name), overwrite = true)
            true
        }
    } catch (_: Exception) {
        false
    }
    android.widget.Toast.makeText(
        ctx,
        if (ok) "Сохранено в «Загрузки/MarkXOBD»" else "Не удалось сохранить файл",
        android.widget.Toast.LENGTH_LONG,
    ).show()
}

private fun shareFile(ctx: android.content.Context, f: File, text: String?) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (f.name.endsWith(".csv")) "text/csv" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Отправить"))
    } catch (_: Exception) {
    }
}

// ---------------- Тесты ----------------

@Composable
private fun TestsScreen(vm: ObdSession) {
    val connState by vm.connState.collectAsState()
    val battery by vm.battery.collectAsState()
    val warmup by vm.warmup.collectAsState()
    val research by vm.research.collectAsState()
    val researchBusy by vm.researchBusy.collectAsState()
    val tick by vm.historyTick.collectAsState()
    val ctx = LocalContext.current
    val connected = connState == ConnState.CONNECTED
    var cmd by rememberSaveable { mutableStateOf("") }
    var showResearch by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ----- Аккумулятор -----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Тест аккумулятора при запуске", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Двигатель заглушён, зажигание включено. Нажмите «Начать» и в течение 30 секунд заводите двигатель.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.startBatteryTest() }, enabled = connected && battery?.running != true) {
                        Text("Начать")
                    }
                    battery?.let { b ->
                        Spacer(Modifier.height(8.dp))
                        Text(b.phase, fontWeight = FontWeight.Medium)
                        val parts = listOfNotNull(
                            b.rest?.let { "покой ${String.format(Locale.US, "%.2f", it)} В" },
                            b.min?.let { "минимум ${String.format(Locale.US, "%.2f", it)} В" },
                            b.charging?.let { "зарядка ${String.format(Locale.US, "%.2f", it)} В" },
                        )
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 14.sp)
                        if (b.samples.size > 2) {
                            LineChart(
                                points = b.samples,
                                startMs = b.samples.first().first,
                                endMs = b.samples.last().first,
                                windowMin = 0,
                                markers = emptyList(),
                                dangerLevel = null,
                                leftLabel = "0 с",
                                rightLabel = "${String.format(Locale.US, "%.0f", (b.samples.last().first - b.samples.first().first) / 1000.0)} с",
                            )
                        }
                        b.verdict?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        // ----- Прогрев -----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Тест прогрева на холостых", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Лучше начинать на холодном двигателе: заведите, нажмите «Начать» и не трогайте машину. " +
                            "Кондиционер и печку выключите. Тест сам определит открытие термостата и включение вентиляторов, " +
                            "запись поездки включится автоматически. При 105 °C тест остановится.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val running = warmup?.running == true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.startWarmupTest() }, enabled = connected && !running) { Text("Начать") }
                        OutlinedButton(onClick = { vm.stopWarmupTest() }, enabled = running) { Text("Остановить") }
                    }
                    warmup?.let { w ->
                        Spacer(Modifier.height(8.dp))
                        val f0 = { v: Double -> String.format(Locale.US, "%.0f", v) }
                        Text(
                            "Прошло ${String.format(Locale.US, "%.1f", w.elapsedMin)} мин · сейчас ${w.current?.let(f0) ?: "?"} °C · " +
                                "старт ${w.startTemp?.let(f0) ?: "?"} °C · макс ${w.max?.let(f0) ?: "?"} °C",
                            fontSize = 14.sp,
                        )
                        Text(
                            "Скорость роста: ${w.ratePerMin?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} °C/мин",
                            fontSize = 14.sp,
                        )
                        Text(
                            "Термостат: " + (
                                w.thermostatTemp?.let { "открылся ≈ при ${f0(it)} °C (на ${String.format(Locale.US, "%.1f", w.thermostatAtMin ?: 0.0)} мин)" }
                                    ?: "ещё не открылся"
                                ),
                            fontSize = 14.sp,
                        )
                        Text(
                            "Вентиляторы: " + if (w.fanTemps.isEmpty()) "не замечены" else w.fanTemps.joinToString { "${f0(it)} °C" },
                            fontSize = 14.sp,
                        )
                        val end = if (tick == 0L) System.currentTimeMillis() else tick
                        val pts = remember(tick) { vm.history.get("coolant", w.startTime) }
                        if (pts.size > 2) {
                            LineChart(
                                points = pts,
                                startMs = w.startTime,
                                endMs = maxOf(end, w.startTime + 60_000),
                                windowMin = 0,
                                markers = emptyList(),
                                dangerLevel = Pids.OVERHEAT_C.toFloat(),
                                leftLabel = "старт",
                            )
                        }
                        w.verdict?.let {
                            Spacer(Modifier.height(6.dp))
                            Text("Итог", fontWeight = FontWeight.Medium)
                            Text(it, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        // ----- Исследование Toyota -----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Расширенные данные Toyota (эксперимент)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Опрашивает блок двигателя запросами Toyota (Mode 21/22) и информацию о блоке. Только чтение — " +
                            "команды записи и управления заблокированы. Результат сохраняется в файл, его можно прислать для расшифровки.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            showResearch = true
                            vm.scanToyota()
                        }, enabled = connected && !researchBusy) { Text("Сканировать") }
                        OutlinedButton(onClick = {
                            vm.saveResearch()?.let { shareFile(ctx, it, null) }
                        }, enabled = research.isNotEmpty() && !researchBusy) { Text("Отправить") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cmd,
                            onValueChange = { cmd = it.uppercase(Locale.US) },
                            label = { Text("Команда (напр. 0105, ATRV)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            showResearch = true
                            vm.sendRaw(cmd)
                        }, enabled = connected && !researchBusy && cmd.isNotBlank()) { Text("▶") }
                    }
                    if (researchBusy) Text("Обмен с блоком…", style = MaterialTheme.typography.bodySmall)
                    if (research.isNotEmpty()) {
                        Row {
                            TextButton(onClick = { showResearch = !showResearch }) {
                                Text(if (showResearch) "Скрыть ответы" else "Показать ответы (${research.size})")
                            }
                            TextButton(onClick = { vm.clearResearch() }) { Text("Очистить") }
                        }
                    }
                }
            }
        }
        if (showResearch) {
            items(research.takeLast(120)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
