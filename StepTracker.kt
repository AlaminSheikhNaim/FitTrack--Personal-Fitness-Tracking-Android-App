package com.example.fittrack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

class StepTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private val history = context.getSharedPreferences("step_history", Context.MODE_PRIVATE)

    var stepsToday by mutableIntStateOf(0)
        private set
    private var lastTotal = -1L

    init { ResetTimes.init(context) }

    // Called now and then: when the steps reset time passes, the count starts again from 0
    fun checkReset() {
        val today = ResetTimes.dayNow("steps").toString()
        if (lastTotal >= 0 && prefs.getString("day", null) != today) {
            prefs.edit().putString("day", today).putLong("baseline", lastTotal).apply()
            stepsToday = 0
        }
    }

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values[0].toLong()      // steps since phone last booted
        lastTotal = total
        val today = ResetTimes.dayNow("steps").toString()
        var baseline = prefs.getLong("baseline", -1L)
        val savedDay = prefs.getString("day", null)

        // New day, first run, or phone rebooted: reset the baseline
        if (savedDay != today || baseline < 0 || total < baseline) {
            baseline = total
            prefs.edit().putString("day", today).putLong("baseline", baseline).apply()
        }
        stepsToday = (total - baseline).toInt()
        history.edit().putInt(today, stepsToday).apply()   // keeps every day for the report
    }

    // Steps per day between two days (only days with steps are in the map)
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        var d = start
        while (!d.isAfter(end)) {
            val v = history.getInt(d.toString(), 0)
            if (v > 0) out[d] = v
            d = d.plusDays(1)
        }
        return out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}