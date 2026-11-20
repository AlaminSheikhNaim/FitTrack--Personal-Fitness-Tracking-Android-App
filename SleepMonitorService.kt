package com.example.fittrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class SleepMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.fittrack.MONITOR_START"
        const val ACTION_STOP = "com.example.fittrack.MONITOR_STOP"
        const val EXTRA_TEST = "test_mode"
        private const val ACTION_WINDOW = "com.example.fittrack.MONITOR_WINDOW"
        private const val CHANNEL_ID = "sleep_monitor"
        private const val NOTIFICATION_ID = 42

        // A one-second sound frame this far above the quiet background counts as an event
        private const val EVENT_DB = 10.0
    }

    private class MotionAcc {
        var sum = 0.0
        var n = 0
        var max = 0.0
    }

    private lateinit var store: MonitorStore
    private lateinit var sensorManager: SensorManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var powerManager: PowerManager

    private val worker = Executors.newSingleThreadExecutor()
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var sessionId = 0L

    private var intervalMs = 600_000L
    private var windowSec = 30
    private var nextPlannedElapsed = 0L
    private var wallOffsetMs = 0L

    // Movement
    private var accel: Sensor? = null
    private var batching = false

    @Volatile
    private var flushLatch: CountDownLatch? = null
    private val motionLock = Any()
    private val buckets = HashMap<Long, MotionAcc>()   // start of minute (ms) -> movement so far
    private var lastMag = -1.0

    // Light and proximity gathered during one window
    private val lightValues = ArrayList<Float>()

    @Volatile
    private var proximityNear = false

    @Volatile
    private var proximityMax = 5f

    // ---- Sensor listeners ----

    // Movement: the size of the change between consecutive acceleration readings, per minute
    private val accelListener = object : SensorEventListener2 {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0].toDouble()
            val y = e.values[1].toDouble()
            val z = e.values[2].toDouble()
            val mag = sqrt(x * x + y * y + z * z)
            val wallMs = wallOffsetMs + e.timestamp / 1_000_000L
            val minute = wallMs / 60_000L * 60_000L
            synchronized(motionLock) {
                if (lastMag >= 0.0) {
                    val d = abs(mag - lastMag)
                    val acc = buckets.getOrPut(minute) { MotionAcc() }
                    acc.sum += d
                    acc.n++
                    if (d > acc.max) acc.max = d
                }
                lastMag = mag
            }
        }

        override fun onFlushCompleted(sensor: Sensor?) {
            flushLatch?.countDown()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            synchronized(lightValues) { lightValues.add(e.values[0]) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val proxListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            proximityNear = e.values[0] < proximityMax
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---- Broadcast receivers ----

    // Screen on/off events show when you picked up the phone
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            val kind = if (intent?.action == Intent.ACTION_SCREEN_ON) "SCREEN_ON" else "SCREEN_OFF"
            val now = System.currentTimeMillis()
            val sid = sessionId
            worker.execute {
                store.addEvent(sid, now, kind)
                store.refreshAsync()
            }
        }
    }

    // The alarm fires this when it is time for the next reading
    private val windowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fittrack:sleep-window")
            wl.acquire((windowSec + 60) * 1000L)
            worker.execute {
                try {
                    runWindow()
                } finally {
                    if (wl.isHeld) wl.release()
                }
            }
        }
    }

    // ---- Service lifecycle ----

    override fun onCreate() {
        super.onCreate()
        store = MonitorStore.get(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                startMonitoring(intent.getBooleanExtra(EXTRA_TEST, false))
            }
            ACTION_STOP -> stopMonitoring()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sleep monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, SleepMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sleep monitoring is on")
            .setContentText("Keep the phone on the bed. Tap Stop when you wake up.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoring(test: Boolean) {
        if (running) return
        intervalMs = if (test) 60_000L else 600_000L
        windowSec = if (test) 10 else 30
        sessionId = store.startSession((intervalMs / 1000).toInt(), windowSec, test)
        wallOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        synchronized(motionLock) {
            buckets.clear()
            lastMag = -1.0
        }

        val thread = HandlerThread("sleep-sensors")
        thread.start()
        sensorThread = thread
        sensorHandler = Handler(thread.looper)

        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        batching = (accel?.fifoMaxEventCount ?: 0) > 0
        if (batching) registerAccel()

        running = true
        ContextCompat.registerReceiver(
            this, windowReceiver, IntentFilter(ACTION_WINDOW), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        // The first reading comes soon, so you can see data without waiting
        val firstDelay = if (test) 5_000L else 60_000L
        nextPlannedElapsed = SystemClock.elapsedRealtime() + firstDelay
        scheduleWindow(nextPlannedElapsed)
    }

    private fun teardown() {
        if (!running) return
        running = false
        alarmManager.cancel(windowPendingIntent())
        runCatching { unregisterReceiver(windowReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(lightListener)
        sensorManager.unregisterListener(proxListener)
        val sid = sessionId
        worker.execute {
            persistMotion(sid, all = true)
            store.endSession(sid)
            store.refreshAsync()
        }
    }

    private fun stopMonitoring() {
        if (running) {
            teardown()
        } else {
            // The service was restarted or killed earlier: just close any leftover session
            worker.execute {
                store.endActiveSessions()
                store.refreshAsync()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        worker.shutdown()
        sensorThread?.quitSafely()
        super.onDestroy()
    }

    // ---- Alarm scheduling ----

    private fun windowPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_WINDOW).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleWindow(atElapsedMs: Long) {
        val pending = windowPendingIntent()
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
        if (canBeExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending
            )
        }
    }

    // ---- Movement ----

    private fun registerAccel() {
        val acc = accel ?: return
        var periodUs = 200_000   // 5 readings per second
        val fifo = acc.fifoMaxEventCount
        if (batching && fifo > 0) {
            // Slow down if the sensor's buffer could not hold a whole interval
            val allowedUs = (intervalMs * 1000L / (fifo * 0.8)).toLong()
            if (allowedUs > periodUs) periodUs = minOf(allowedUs, 1_000_000L).toInt()
        }
        val latencyUs = if (batching) {
            (intervalMs * 2000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
        sensorManager.registerListener(accelListener, acc, periodUs, latencyUs, sensorHandler)
    }

    // Saves finished minutes of movement and returns (average, strongest) for this block
    private fun persistMotion(sid: Long, all: Boolean): Pair<Double?, Double?> {
        val currentMinute = System.currentTimeMillis() / 60_000L * 60_000L
        val rows = ArrayList<MotionRow>()
        synchronized(motionLock) {
            val iter = buckets.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (all || entry.key < currentMinute) {
                    val acc = entry.value
                    if (acc.n > 0) rows.add(MotionRow(entry.key, acc.sum / acc.n, acc.max, acc.n))
                    iter.remove()
                }
            }
        }
        store.addMotion(sid, rows)
        if (rows.isEmpty()) return Pair(null, null)
        var weighted = 0.0
        var count = 0
        var peak = 0.0
        for (r in rows) {
            weighted += r.mad * r.n
            count += r.n
            if (r.max > peak) peak = r.max
        }
        return Pair(weighted / count, peak)
    }

    // ---- One reading ----

    private fun runWindow() {
        if (!running) return
        val sid = sessionId
        val windowStart = System.currentTimeMillis()

        // 1) Movement: collect what the sensor chip buffered (or sample live for this window)
        val acc = accel
        if (acc != null) {
            if (batching) {
                val latch = CountDownLatch(1)
                flushLatch = latch
                if (sensorManager.flush(accelListener)) latch.await(3, TimeUnit.SECONDS)
            } else {
                synchronized(motionLock) { lastMag = -1.0 }
                registerAccel()
            }
        }

        // 2) Light and proximity, only while this window is open
        synchronized(lightValues) { lightValues.clear() }
        proximityNear = false
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (light != null) {
            sensorManager.registerListener(
                lightListener, light, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler
            )
        }
        if (prox != null) {
            proximityMax = prox.maximumRange
            sensorManager.registerListener(
                proxListener, prox, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler
            )
        }

        // 3) Sound
        val sound = measureSound()

        // 4) Release everything until the next window
        sensorManager.unregisterListener(lightListener)
        sensorManager.unregisterListener(proxListener)
        if (acc != null && !batching) sensorManager.unregisterListener(accelListener)
        if (!running) return

        val lux = synchronized(lightValues) { median(ArrayList(lightValues)) }
        val motion = persistMotion(sid, all = false)
        store.addBlock(sid, windowStart, lux, proximityNear, sound, motion.first, motion.second)
        store.refreshAsync()

        // 5) Schedule the next reading, keeping the rhythm steady
        var next = nextPlannedElapsed + intervalMs
        val now = SystemClock.elapsedRealtime()
        if (next <= now + 2000) next = now + intervalMs
        nextPlannedElapsed = next
        scheduleWindow(next)
    }

    private fun median(values: List<Float>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    // ---- Sound ----

    // Records short frames, keeps only loudness numbers (never the audio) and returns
    // the quiet background level, the loudest moment and the number of sudden sounds.
    @SuppressLint("MissingPermission")
    private fun measureSound(): SoundResult {
        val none = SoundResult(null, null, 0)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return none

        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return none

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, rate * 2)
            )
        } catch (e: Exception) {
            return none
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return none
        }

        val frame = ShortArray(rate)   // one second
        val levels = ArrayList<Double>()
        try {
            recorder.startRecording()
            repeat(windowSec) {
                if (!running) return@repeat
                var read = 0
                while (read < frame.size) {
                    val n = recorder.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == frame.size) {
                    val db = frameLevelDb(frame)
                    if (db != null) levels.add(db)
                }
            }
        } catch (e: Exception) {
            // The microphone was busy or blocked: report no sound data for this window
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (levels.size < 3) return none
        val sorted = levels.sorted()
        val floor = sorted[(sorted.size * 0.2).toInt()]   // the quiet background, e.g. a fan
        val peak = sorted.last()
        val events = levels.count { it > floor + EVENT_DB }
        return SoundResult(floor, peak, events)
    }

    // Loudness of one second in dB relative to full scale (not calibrated sound pressure).
    // A simple high-pass filter (about 150 Hz) removes wind and low rumble first.
    private fun frameLevelDb(frame: ShortArray): Double? {
        val a = 0.944
        var prevX = 0.0
        var prevY = 0.0
        var sumSq = 0.0
        var raw = 0L
        for (s in frame) {
            val x = s.toDouble()
            val y = a * (prevY + x - prevX)
            prevX = x
            prevY = y
            sumSq += y * y
            raw += abs(s.toInt())
        }
        if (raw == 0L) return null   // pure silence: the system is blocking the microphone
        val rms = sqrt(sumSq / frame.size)
        return 20.0 * log10(rms / 32768.0 + 1e-9)
    }
}