package kg.bishkek.markxobd

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Сервис переднего плана: держит процесс живым, пока идёт связь с адаптером,
 * чтобы опрос и запись не останавливались при выключенном экране.
 */
class ObdService : Service() {

    companion object {
        private const val CHANNEL_STATUS = "obd_status"
        private const val CHANNEL_ALERT = "obd_alert"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ObdService::class.java))
            } catch (_: Exception) {
                // запуск из фона может быть запрещён системой — сессия всё равно работает, пока жив процесс
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, ObdService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        var type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        // тип «местоположение» нужен, чтобы GPS писался при выключенном экране; без разрешения его указывать нельзя
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildStatus("Подключение…"), type)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        acquireWakeLock()
        val session = ObdSession.get(application as Application)
        scope.launch {
            while (isActive) {
                updateNotification(session)
                delay(3000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MarkXObd::session").apply { acquire() }
        } catch (_: Exception) {
        }
    }

    private fun updateNotification(s: ObdSession) {
        val v = s.values.value
        val parts = ArrayList<String>()
        when (s.connState.value) {
            ConnState.CONNECTED -> {
                v["coolant"]?.let { parts.add("ОЖ ${String.format(Locale.US, "%.0f", it)} °C") }
                (v["ecuV"] ?: v[Pids.ADAPTER_V])?.let { parts.add(String.format(Locale.US, "%.1f В", it)) }
                v["rpm"]?.let { parts.add("${String.format(Locale.US, "%.0f", it)} об/мин") }
            }
            else -> parts.add(s.status.value)
        }
        val volt = v["ecuV"] ?: v[Pids.ADAPTER_V]
        val connected = s.connState.value == ConnState.CONNECTED
        ObdWidget.push(
            this,
            if (connected) v["coolant"] else null,
            if (connected) volt else null,
            when {
                !connected -> "Нет связи"
                s.recordingFile.value != null -> "Идёт запись"
                else -> "На связи"
            },
        )
        if (s.recordingFile.value != null) parts.add("запись")
        if (s.warmup.value?.running == true) parts.add("тест прогрева")
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.notify(NOTIF_ID, buildStatus(parts.joinToString(" · ")))
            if (s.overheat.value && !alertShown) {
                alertShown = true
                nm.notify(ALERT_ID, buildAlert(v["coolant"]))
            } else if (!s.overheat.value && alertShown) {
                alertShown = false
                nm.cancel(ALERT_ID)
            }
        } catch (_: SecurityException) {
            // нет разрешения на уведомления — работаем без них
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildStatus(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mark X OBD")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildAlert(coolant: Double?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Перегрев двигателя")
            .setContentText("ОЖ ${coolant?.let { String.format(Locale.US, "%.0f", it) } ?: "?"} °C — остановитесь и заглушите двигатель")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Связь с адаптером", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Перегрев", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        ObdWidget.push(this, null, null, "Нет связи")
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
