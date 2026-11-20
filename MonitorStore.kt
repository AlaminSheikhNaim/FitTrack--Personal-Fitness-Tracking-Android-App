package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

// One reading: light, proximity, sound and movement
data class MonitorBlock(
    val tsMs: Long,
    val lightLux: Double?,
    val covered: Boolean,
    val noiseFloorDb: Double?,
    val noisePeakDb: Double?,
    val noiseEvents: Int,
    val motionMean: Double?,
    val motionMax: Double?
)

// Movement summary for one minute (written by the service)
class MotionRow(val minuteMs: Long, val mad: Double, val max: Double, val n: Int)

// Result of one sound sample (written by the service)
class SoundResult(val floorDb: Double?, val peakDb: Double?, val events: Int)

// Used by the analysis
data class MinuteMotion(val minuteMs: Long, val mad: Double, val max: Double)

data class ScreenEvent(val tsMs: Long, val kind: String)

data class SessionInfo(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val intervalS: Int,
    val windowS: Int,
    val test: Boolean
)

// One thing that disturbed sleep. kind is LIGHT, SOUND, MOVEMENT or PHONE.
data class Disturbance(val tsMs: Long, val kind: String, val text: String)

// The finished sleep quality report for one monitored night. A score of -1 means "not rated".
data class QualityReport(
    val sessionId: Long,
    val day: String,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val movementScore: Int,
    val soundScore: Int,
    val lightScore: Int,
    val interruptionScore: Int,
    val durationMin: Int,
    val asleepMin: Int,
    val latencyMin: Int,
    val interruptions: Int,
    val restlessMoves: Int,
    val soundEvents: Int,
    val lightEvents: Int,
    val test: Boolean,
    val summary: String,
    val tips: String,
    val disturbances: List<Disturbance>
)

// The sleep time guessed from the last monitored night (used for the wake-up question)
data class WakeEstimate(val sessionId: Long, val startMs: Long, val endMs: Long)

class MonitorStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "monitor.db", null, 2) {

    companion object {
        @Volatile
        private var instance: MonitorStore? = null

        // One shared instance for the whole app, used by both the screen and the service
        fun get(context: Context): MonitorStore =
            instance ?: synchronized(this) {
                instance ?: MonitorStore(context.applicationContext).also { instance = it }
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val builder = Executors.newSingleThreadExecutor()

    @Volatile
    private var building = false

    var sessionId by mutableLongStateOf(0L)
        private set
    var startedAtMs by mutableLongStateOf(0L)
        private set
    var endedAtMs by mutableLongStateOf(0L)   // 0 while the session is still running
        private set
    var intervalS by mutableIntStateOf(600)
        private set
    var blockCount by mutableIntStateOf(0)
        private set
    var eventCount by mutableIntStateOf(0)
        private set
    var lastBlockMs by mutableLongStateOf(0L)
        private set
    val recentBlocks = mutableStateListOf<MonitorBlock>()

    // All finished reports, newest first
    val reports = mutableStateListOf<QualityReport>()

    val isActive: Boolean get() = sessionId > 0L && endedAtMs == 0L

    // Reports are kept (the newest 400 nights). Nothing is cleared at 12:00 am.
    val latestReport: QualityReport? get() = reports.firstOrNull()

    // Wake-up question: the estimated sleep of the last finished night, until it is answered
    private val prefs = context.getSharedPreferences("wake_prompt", Context.MODE_PRIVATE)
    private var answeredSessionId: Long = prefs.getLong("answered", 0L)
    var wakeEstimate by mutableStateOf<WakeEstimate?>(null)
        private set

    init {
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE monitor_session (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "start_ms INTEGER NOT NULL, " +
                    "end_ms INTEGER, " +
                    "interval_s INTEGER NOT NULL, " +
                    "window_s INTEGER NOT NULL, " +
                    "test INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE monitor_block (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "light_lux REAL, " +
                    "covered INTEGER NOT NULL, " +
                    "noise_floor_db REAL, " +
                    "noise_peak_db REAL, " +
                    "noise_events INTEGER NOT NULL, " +
                    "motion_mean REAL, " +
                    "motion_max REAL)"
        )
        db.execSQL(
            "CREATE TABLE monitor_motion (" +
                    "session_id INTEGER NOT NULL, " +
                    "minute_ms INTEGER NOT NULL, " +
                    "mad REAL NOT NULL, " +
                    "mx REAL NOT NULL, " +
                    "n INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE monitor_event (" +
                    "session_id INTEGER NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL)"
        )
        createReportTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createReportTable(db)
    }

    private fun createReportTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS quality_report (" +
                    "session_id INTEGER PRIMARY KEY, " +
                    "day TEXT NOT NULL, " +
                    "start_ms INTEGER NOT NULL, " +
                    "end_ms INTEGER NOT NULL, " +
                    "score INTEGER NOT NULL, " +
                    "movement INTEGER NOT NULL, " +
                    "sound INTEGER NOT NULL, " +
                    "light INTEGER NOT NULL, " +
                    "interrupt INTEGER NOT NULL, " +
                    "duration_min INTEGER NOT NULL, " +
                    "asleep_min INTEGER NOT NULL, " +
                    "latency_min INTEGER NOT NULL, " +
                    "interruptions INTEGER NOT NULL, " +
                    "moves INTEGER NOT NULL, " +
                    "sound_events INTEGER NOT NULL, " +
                    "light_events INTEGER NOT NULL, " +
                    "test INTEGER NOT NULL, " +
                    "summary TEXT NOT NULL, " +
                    "tips TEXT NOT NULL, " +
                    "details TEXT NOT NULL)"
        )
    }

    // ---------- State for the screen (call on the main thread) ----------

    fun refresh() {
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, interval_s FROM monitor_session ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                sessionId = c.getLong(0)
                startedAtMs = c.getLong(1)
                endedAtMs = c.getLong(2)
                intervalS = c.getInt(3)
            } else {
                sessionId = 0L
                startedAtMs = 0L
                endedAtMs = 0L
            }
        }

        recentBlocks.clear()
        blockCount = 0
        eventCount = 0
        lastBlockMs = 0L
        if (sessionId != 0L) {
            val args = arrayOf(sessionId.toString())
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM monitor_block WHERE session_id = ?", args
            ).use { c ->
                if (c.moveToFirst()) {
                    blockCount = c.getInt(0)
                }
            }
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM monitor_event WHERE session_id = ?", args
            ).use { c ->
                if (c.moveToFirst()) {
                    eventCount = c.getInt(0)
                }
            }
            readableDatabase.rawQuery(
                "SELECT ts_ms, light_lux, covered, noise_floor_db, noise_peak_db, " +
                        "noise_events, motion_mean, motion_max FROM monitor_block " +
                        "WHERE session_id = ? ORDER BY ts_ms DESC LIMIT 20",
                args
            ).use { c ->
                while (c.moveToNext()) {
                    recentBlocks.add(readBlock(c))
                }
            }
            lastBlockMs = recentBlocks.firstOrNull()?.tsMs ?: 0L
        }

        loadReports()
        scheduleReportBuild()
    }

    // Safe to call from any thread
    fun refreshAsync() {
        main.post { refresh() }
    }

    private fun readBlock(c: android.database.Cursor): MonitorBlock = MonitorBlock(
        tsMs = c.getLong(0),
        lightLux = if (c.isNull(1)) null else c.getDouble(1),
        covered = c.getInt(2) == 1,
        noiseFloorDb = if (c.isNull(3)) null else c.getDouble(3),
        noisePeakDb = if (c.isNull(4)) null else c.getDouble(4),
        noiseEvents = c.getInt(5),
        motionMean = if (c.isNull(6)) null else c.getDouble(6),
        motionMax = if (c.isNull(7)) null else c.getDouble(7)
    )

    // ---------- Recording (called by the service) ----------

    fun startSession(intervalS: Int, windowS: Int, test: Boolean): Long {
        endActiveSessions()
        val values = ContentValues().apply {
            put("start_ms", System.currentTimeMillis())
            put("interval_s", intervalS)
            put("window_s", windowS)
            put("test", if (test) 1 else 0)
        }
        val id = writableDatabase.insert("monitor_session", null, values)
        refreshAsync()
        return id
    }

    fun endSession(id: Long) {
        val values = ContentValues().apply { put("end_ms", System.currentTimeMillis()) }
        writableDatabase.update("monitor_session", values, "id = ?", arrayOf(id.toString()))
    }

    fun endActiveSessions() {
        val values = ContentValues().apply { put("end_ms", System.currentTimeMillis()) }
        writableDatabase.update("monitor_session", values, "end_ms IS NULL", null)
    }

    fun addBlock(
        sid: Long,
        tsMs: Long,
        lux: Double?,
        covered: Boolean,
        sound: SoundResult,
        motionMean: Double?,
        motionMax: Double?
    ) {
        val v = ContentValues().apply {
            put("session_id", sid)
            put("ts_ms", tsMs)
            if (lux != null) put("light_lux", lux) else putNull("light_lux")
            put("covered", if (covered) 1 else 0)
            if (sound.floorDb != null) put("noise_floor_db", sound.floorDb) else putNull("noise_floor_db")
            if (sound.peakDb != null) put("noise_peak_db", sound.peakDb) else putNull("noise_peak_db")
            put("noise_events", sound.events)
            if (motionMean != null) put("motion_mean", motionMean) else putNull("motion_mean")
            if (motionMax != null) put("motion_max", motionMax) else putNull("motion_max")
        }
        writableDatabase.insert("monitor_block", null, v)
    }

    fun addMotion(sid: Long, rows: List<MotionRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("session_id", sid)
                    put("minute_ms", r.minuteMs)
                    put("mad", r.mad)
                    put("mx", r.max)
                    put("n", r.n)
                }
                db.insert("monitor_motion", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addEvent(sid: Long, tsMs: Long, kind: String) {
        val v = ContentValues().apply {
            put("session_id", sid)
            put("ts_ms", tsMs)
            put("kind", kind)
        }
        writableDatabase.insert("monitor_event", null, v)
    }

    // ---------- Reports ----------

    // Deletes the saved reports so every finished night is analysed again
    fun reanalyze() {
        writableDatabase.delete("quality_report", null, null)
        refreshAsync()
    }

    // ---------- Wake-up question ----------

    // Phone pick-ups close together at the end of the session mean you were up.
    // The wake-up time is where that last run of screen-on activity began.
    private fun estimateWakeMs(r: QualityReport): Long {
        var wake = r.endMs
        val pickups = eventsFor(r.sessionId)
            .filter { it.kind == "SCREEN_ON" && it.tsMs <= r.endMs }
            .sortedByDescending { it.tsMs }
        for (e in pickups) {
            if (wake - e.tsMs <= 30 * 60_000L) wake = e.tsMs else break
        }
        return wake
    }

    // Asks about the newest finished night only, once, and only if it ended in the last 18 hours
    private fun updateWakeEstimate() {
        wakeEstimate = null
        val r = reports.firstOrNull() ?: return
        if (r.test || r.score < 0 || r.sessionId == answeredSessionId) return
        if (System.currentTimeMillis() - r.endMs > 18 * 3_600_000L) return
        val startMs = r.startMs + r.latencyMin * 60_000L   // when you fell asleep (sensors + screen)
        val wakeMs = estimateWakeMs(r)
        if (wakeMs - startMs < 20 * 60_000L) return
        wakeEstimate = WakeEstimate(r.sessionId, startMs, wakeMs)
    }

    // Call after the person saves or skips the question, so it is not asked again
    fun finishWakeQuestion() {
        val e = wakeEstimate ?: return
        answeredSessionId = e.sessionId
        prefs.edit().putLong("answered", e.sessionId).apply()
        wakeEstimate = null
    }

    private fun encodeDetails(list: List<Disturbance>): String =
        list.joinToString("\n") {
            "${it.tsMs}|${it.kind}|${it.text.replace('\n', ' ').replace('|', '/')}"
        }

    private fun decodeDetails(s: String): List<Disturbance> {
        if (s.isEmpty()) return emptyList()
        return s.split("\n").mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) null
            else parts[0].toLongOrNull()?.let { Disturbance(it, parts[1], parts[2]) }
        }
    }

    private fun loadReports() {
        reports.clear()
        readableDatabase.rawQuery(
            "SELECT session_id, day, start_ms, end_ms, score, movement, sound, light, " +
                    "interrupt, duration_min, asleep_min, latency_min, interruptions, moves, " +
                    "sound_events, light_events, test, summary, tips, details " +
                    "FROM quality_report ORDER BY end_ms DESC LIMIT 400",
            null
        ).use { c ->
            while (c.moveToNext()) {
                reports.add(
                    QualityReport(
                        sessionId = c.getLong(0),
                        day = c.getString(1),
                        startMs = c.getLong(2),
                        endMs = c.getLong(3),
                        score = c.getInt(4),
                        movementScore = c.getInt(5),
                        soundScore = c.getInt(6),
                        lightScore = c.getInt(7),
                        interruptionScore = c.getInt(8),
                        durationMin = c.getInt(9),
                        asleepMin = c.getInt(10),
                        latencyMin = c.getInt(11),
                        interruptions = c.getInt(12),
                        restlessMoves = c.getInt(13),
                        soundEvents = c.getInt(14),
                        lightEvents = c.getInt(15),
                        test = c.getInt(16) == 1,
                        summary = c.getString(17),
                        tips = c.getString(18),
                        disturbances = decodeDetails(c.getString(19))
                    )
                )
            }
        }
        updateWakeEstimate()
    }

    private fun saveReport(r: QualityReport) {
        val v = ContentValues().apply {
            put("session_id", r.sessionId)
            put("day", r.day)
            put("start_ms", r.startMs)
            put("end_ms", r.endMs)
            put("score", r.score)
            put("movement", r.movementScore)
            put("sound", r.soundScore)
            put("light", r.lightScore)
            put("interrupt", r.interruptionScore)
            put("duration_min", r.durationMin)
            put("asleep_min", r.asleepMin)
            put("latency_min", r.latencyMin)
            put("interruptions", r.interruptions)
            put("moves", r.restlessMoves)
            put("sound_events", r.soundEvents)
            put("light_events", r.lightEvents)
            put("test", if (r.test) 1 else 0)
            put("summary", r.summary)
            put("tips", r.tips)
            put("details", encodeDetails(r.disturbances))
        }
        writableDatabase.insertWithOnConflict(
            "quality_report", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // Analyses every finished session that has no report yet, in the background
    private fun scheduleReportBuild() {
        if (building) return
        building = true
        builder.execute {
            try {
                val pending = pendingSessions()
                for (s in pending) {
                    val report = try {
                        SleepAnalyzer.analyze(s, blocksFor(s.id), motionFor(s.id), eventsFor(s.id))
                    } catch (e: Exception) {
                        SleepAnalyzer.failed(s)
                    }
                    saveReport(report)
                }
                if (pending.isNotEmpty()) refreshAsync()
            } finally {
                building = false
            }
        }
    }

    private fun pendingSessions(): List<SessionInfo> {
        val list = ArrayList<SessionInfo>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, interval_s, window_s, test FROM monitor_session " +
                    "WHERE end_ms IS NOT NULL AND id NOT IN (SELECT session_id FROM quality_report) " +
                    "ORDER BY id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SessionInfo(
                        c.getLong(0), c.getLong(1), c.getLong(2),
                        c.getInt(3), c.getInt(4), c.getInt(5) == 1
                    )
                )
            }
        }
        return list
    }

    private fun blocksFor(sid: Long): List<MonitorBlock> {
        val list = ArrayList<MonitorBlock>()
        readableDatabase.rawQuery(
            "SELECT ts_ms, light_lux, covered, noise_floor_db, noise_peak_db, " +
                    "noise_events, motion_mean, motion_max FROM monitor_block " +
                    "WHERE session_id = ? ORDER BY ts_ms ASC",
            arrayOf(sid.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(readBlock(c))
            }
        }
        return list
    }

    private fun motionFor(sid: Long): List<MinuteMotion> {
        val list = ArrayList<MinuteMotion>()
        readableDatabase.rawQuery(
            "SELECT minute_ms, mad, mx FROM monitor_motion WHERE session_id = ? ORDER BY minute_ms",
            arrayOf(sid.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(MinuteMotion(c.getLong(0), c.getDouble(1), c.getDouble(2)))
            }
        }
        return list
    }

    private fun eventsFor(sid: Long): List<ScreenEvent> {
        val list = ArrayList<ScreenEvent>()
        readableDatabase.rawQuery(
            "SELECT ts_ms, kind FROM monitor_event WHERE session_id = ? ORDER BY ts_ms",
            arrayOf(sid.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(ScreenEvent(c.getLong(0), c.getString(1)))
            }
        }
        return list
    }
}