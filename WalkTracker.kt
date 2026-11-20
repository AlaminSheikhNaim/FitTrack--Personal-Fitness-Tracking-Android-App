package com.example.fittrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Records a walk or run with the phone's GPS while the app is open (Workout > Start)
object WalkTracker : LocationListener {
    var active by mutableStateOf(false)
        private set
    var type by mutableStateOf("Walk")
    var startMs by mutableLongStateOf(0L)
        private set
    var distanceM by mutableDoubleStateOf(0.0)
        private set
    var speedKmh by mutableDoubleStateOf(0.0)
        private set
    var maxKmh by mutableDoubleStateOf(0.0)
        private set
    var tempC by mutableStateOf<Double?>(null)
        private set
    var hasFix by mutableStateOf(false)
        private set
    val points = mutableStateListOf<Pair<Double, Double>>()

    // True while the low-power background service keeps recording with the screen off
    var inBackground by mutableStateOf(false)
        private set
    private var slow = false
    private var idleFixes = 0
    private var appContext: Context? = null
    private var manager: LocationManager? = null
    private var last: Location? = null
    private var tempAsked = false

    @SuppressLint("MissingPermission")
    fun start(context: Context, kind: String, background: Boolean): Boolean {
        if (active) return true
        val app = context.applicationContext
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            appContext = app
            slow = false; idleFixes = 0
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FAST_MS, FAST_M, this, Looper.getMainLooper())
            manager = lm
            inBackground = background
            if (background) {
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        app, android.content.Intent(app, WalkService::class.java)
                    )
                } catch (e: Exception) { inBackground = false }
            }
            type = kind; startMs = System.currentTimeMillis()
            distanceM = 0.0; speedKmh = 0.0; maxKmh = 0.0; tempC = null
            hasFix = false; last = null; tempAsked = false
            points.clear()
            active = true
            true
        } catch (e: Exception) { false }
    }

    class Result(
        val type: String, val startMs: Long, val endMs: Long, val distanceM: Double,
        val maxKmh: Double, val tempC: Double?, val route: String
    )

    // Very low power: fewer GPS wake-ups while you stand still
    @SuppressLint("MissingPermission")
    private fun setRate(slowMode: Boolean) {
        val lm = manager ?: return
        if (slow == slowMode) return
        slow = slowMode
        try {
            lm.removeUpdates(this)
            if (slowMode) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, SLOW_MS, SLOW_M, this, Looper.getMainLooper())
            else lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FAST_MS, FAST_M, this, Looper.getMainLooper())
        } catch (e: Exception) { }
    }

    private const val FAST_MS = 4000L
    private const val FAST_M = 5f
    private const val SLOW_MS = 20000L
    private const val SLOW_M = 12f

    fun stop(): Result {
        manager?.removeUpdates(this)
        manager = null
        active = false
        if (inBackground) appContext?.let { it.stopService(android.content.Intent(it, WalkService::class.java)) }
        inBackground = false
        return Result(type, startMs, System.currentTimeMillis(), distanceM, maxKmh, tempC, encodeRoute())
    }

    override fun onLocationChanged(loc: Location) {
        if (!active || loc.hasAccuracy() && loc.accuracy > 30f) return
        hasFix = true
        val prev = last
        if (prev == null) {
            last = loc
            points.add(loc.latitude to loc.longitude)
            if (!tempAsked) { tempAsked = true; fetchTemperature(loc.latitude, loc.longitude) }
            return
        }
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.time - prev.time) / 1000.0
        if (d < 2.0 || dt <= 0.0) {
            speedKmh = 0.0
            if (++idleFixes >= 6) setRate(true)
            return
        }
        idleFixes = 0
        if (slow) setRate(false)
        val v = d / dt * 3.6
        if (v > 40.0) return   // GPS jump, ignore
        distanceM += d
        speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else v
        if (speedKmh > maxKmh) maxKmh = speedKmh
        points.add(loc.latitude to loc.longitude)
        last = loc
    }

    private fun fetchTemperature(lat: Double, lon: Double) {
        Thread {
            try {
                val url = URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m", lat, lon))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                tempC = JSONObject(text).getJSONObject("current").getDouble("temperature_2m")
            } catch (e: Exception) { /* no internet: temperature stays n/a */ }
        }.start()
    }

    private fun encodeRoute(): String {
        val list = points.toList()
        val step = (list.size / 400) + 1
        return list.filterIndexed { i, _ -> i % step == 0 || i == list.lastIndex }
            .joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.first, it.second) }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

fun decodeRoute(route: String): List<Pair<Double, Double>> =
    route.split(";").mapNotNull {
        val p = it.split(",")
        if (p.size == 2) (p[0].toDoubleOrNull() ?: return@mapNotNull null) to (p[1].toDoubleOrNull() ?: return@mapNotNull null) else null
    }
