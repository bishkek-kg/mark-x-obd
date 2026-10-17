package kg.bishkek.markxobd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.util.Locale

/** Виджет на рабочий стол: температура ОЖ, напряжение и состояние связи. */
class ObdWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context, null, null, "Откройте приложение")
    }

    companion object {
        @Volatile
        private var lastText = ""

        fun push(context: Context, coolant: Double?, volt: Double?, status: String) {
            val text = "$coolant|$volt|$status"
            if (text == lastText) return
            lastText = text
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ObdWidget::class.java))
                if (ids.isEmpty()) return
                val v = RemoteViews(context.packageName, R.layout.widget_obd)
                v.setTextViewText(R.id.w_coolant, coolant?.let { "${it.toInt()}°" } ?: "—")
                v.setTextViewText(R.id.w_volt, volt?.let { String.format(Locale.US, "%.1f В", it) } ?: "— В")
                v.setTextViewText(R.id.w_status, status)
                val color = when {
                    coolant == null -> Color.WHITE
                    coolant >= Thresholds.overheat -> Color.rgb(255, 90, 78)
                    coolant >= Thresholds.warn -> Color.rgb(255, 201, 74)
                    else -> Color.rgb(140, 230, 140)
                }
                v.setTextColor(R.id.w_coolant, color)
                v.setTextColor(
                    R.id.w_volt,
                    if (volt != null && volt < Thresholds.lowV) Color.rgb(255, 201, 74) else Color.WHITE,
                )
                val open = PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                v.setOnClickPendingIntent(R.id.w_root, open)
                manager.updateAppWidget(ids, v)
            } catch (_: Exception) {
            }
        }
    }
}
