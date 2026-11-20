package com.example.fittrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

// Takes ONE short reading window (default 30 s). Call it from a background thread, never the UI thread.
// Sensors and the microphone are switched on only inside this window and fully released afterwards.
// Sound is never saved: only a loudness number and a count of standout sounds are kept.
class SleepSampler(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Collects light, proximity and movement while the window is open.
    private class Collector(private val proxMax: Float) : SensorEventListener {
        private var luxSum = 0.0
        private var luxCount = 0
        var near = false
            private set
        private var accelCount = 0
        private var accelMean = 0.0
        private var accelM2 = 0.0

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    luxSum += event.values[0]
                    luxCount++
                }
                Sensor.TYPE_PROXIMITY -> {
                    near = event.values[0] < minOf(proxMax, 3f)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0].toDouble()
                    val y = event.values[1].toDouble()
                    val z = event.values[2].toDouble()
                    val magnitude = sqrt(x * x + y * y + z * z)
                    accelCount++
                    val delta = magnitude - accelMean
                    accelMean += delta / accelCount
                    accelM2 += delta * (magnitude - accelMean)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        fun averageLux(): Float = if (luxCount == 0) 0f else (luxSum / luxCount).toFloat()

        fun movement(): Float =
            if (accelCount < 2) 0f else sqrt(accelM2 / accelCount).toFloat()
    }

    fun sample(durationMs: Long = 30_000L): SleepSample {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitTrack:SleepSample")
        wakeLock.acquire(durationMs + 10_000L)   // safety timeout: it can never stay on by accident

        val thread = HandlerThread("sleep-sensors")
        thread.start()
        val handler = Handler(thread.looper)
        val collector = Collector(proximitySensor?.maximumRange ?: 5f)
        val endTime = SystemClock.elapsedRealtime() + durationMs

        var soundDb = SILENT_DB
        var soundEvents = 0
        try {
            lightSensor?.let {
                sensorManager.registerListener(collector, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
            proximitySensor?.let {
                sensorManager.registerListener(collector, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
            accelSensor?.let {
                sensorManager.registerListener(collector, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }

            val sound = listenToSound(endTime)
            soundDb = sound.first
            soundEvents = sound.second

            // If the microphone was not available, still wait out the window for the other sensors.
            val remaining = endTime - SystemClock.elapsedRealtime()
            if (remaining > 0) Thread.sleep(remaining)
        } finally {
            sensorManager.unregisterListener(collector)
            thread.quitSafely()
            thread.join(1000)
            if (wakeLock.isHeld) wakeLock.release()
        }

        return SleepSample(
            tsMs = System.currentTimeMillis(),
            lux = collector.averageLux(),
            covered = collector.near,
            noiseDb = soundDb,
            noiseEvents = soundEvents,
            movement = collector.movement()
        )
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, bufferBytes: Int): AudioRecord? = try {
        val record = AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferBytes
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            record.release()
            null
        }
    } catch (e: Exception) {
        null
    }

    // Returns (typical loudness in dB, number of standout sounds).
    // Steady noise such as a fan or wind sets the "typical" level, so it does not count as an event.
    // Only sounds clearly above that level (cough, snoring bursts, talking, a door) are counted.
    private fun listenToSound(endTime: Long): Pair<Float, Int> {
        if (!hasMicPermission()) return Pair(SILENT_DB, 0)

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return Pair(SILENT_DB, 0)
        val frameSize = SAMPLE_RATE / 10                       // 100 ms of audio
        val bufferBytes = max(minBuffer, frameSize * 2 * 4)

        // UNPROCESSED avoids auto-gain, which would make a quiet room look noisy.
        val record = createRecord(MediaRecorder.AudioSource.UNPROCESSED, bufferBytes)
            ?: createRecord(MediaRecorder.AudioSource.MIC, bufferBytes)
            ?: return Pair(SILENT_DB, 0)

        val levels = ArrayList<Float>()
        val frame = ShortArray(frameSize)
        try {
            record.startRecording()
            while (SystemClock.elapsedRealtime() < endTime) {
                val read = record.read(frame, 0, frameSize)
                if (read <= 1) break
                // First difference = a cheap high-pass filter. It weakens low rumble (wind, fan hum)
                // so that coughs, speech and snoring stand out more.
                var sum = 0.0
                for (i in 1 until read) {
                    val d = (frame[i] - frame[i - 1]).toDouble()
                    sum += d * d
                }
                val rms = sqrt(sum / (read - 1))
                levels.add((20.0 * log10(max(rms, 1.0) / 32768.0)).toFloat())
            }
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
            }
            record.release()
        }

        if (levels.size < 5) return Pair(SILENT_DB, 0)

        val sorted = levels.sorted()
        val typical = sorted[sorted.size / 2]                  // median = the steady background
        val threshold = typical + EVENT_MARGIN_DB

        var events = 0
        var inEvent = false
        var quietFrames = 0
        for (level in levels) {
            val loud = level > threshold && level > MIN_EVENT_DB
            if (loud) {
                if (!inEvent) {
                    events++
                    inEvent = true
                }
                quietFrames = 0
            } else if (inEvent) {
                quietFrames++
                if (quietFrames >= 2) inEvent = false
            }
        }
        return Pair(typical, events)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val SILENT_DB = -100f
        // Tuning values: a sound must be this many dB above the steady background...
        private const val EVENT_MARGIN_DB = 10f
        // ...and also louder than this absolute level, to ignore tiny rustles in a silent room.
        private const val MIN_EVENT_DB = -60f
    }
}