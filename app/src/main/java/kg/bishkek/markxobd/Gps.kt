package kg.bishkek.markxobd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/** Координаты для карты поездки. Работает от встроенного GPS, без сервисов Google. */
class GpsTracker(private val context: Context) {
    @Volatile
    var last: Location? = null
        private set
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            last = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            running = true
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running) return
        try {
            context.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        running = false
        last = null
    }

    /** Свежая точка (не старше 5 с) — в запись. */
    fun freshValues(now: Long): Map<String, Double> {
        val l = last ?: return emptyMap()
        if (now - l.time > 5_000) return emptyMap()
        return mapOf("lat" to l.latitude, "lon" to l.longitude)
    }
}
