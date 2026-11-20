package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Stores one row per monitoring night ("session") and one row per sampling window.
// Use SleepQualityStore.get(context) so the screen and the background service share one instance.
class SleepQualityStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "sleep_quality.db", null, 1) {

    var activeSessionId by mutableLongStateOf(0L)      // 0 means monitoring is off
        private set
    var activeSinceMs by mutableLongStateOf(0L)
        private set
    var lastResult by mutableStateOf<SleepQualityResult?>(null)   // score of the last finished night
        private set

    init {
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE monitor_sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "start_ms INTEGER NOT NULL, " +
                    "end_ms INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE samples (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "lux REAL NOT NULL, " +
                    "covered INTEGER NOT NULL, " +
                    "noise_db REAL NOT NULL, " +
                    "noise_events INTEGER NOT NULL, " +
                    "movement REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun refresh() {
        readableDatabase.rawQuery(
            "SELECT id, start_ms FROM monitor_sessions WHERE end_ms IS NULL ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                activeSessionId = c.getLong(0)
                activeSinceMs = c.getLong(1)
            } else {
                activeSessionId = 0L
                activeSinceMs = 0L
            }
        }

        var lastId = 0L
        readableDatabase.rawQuery(
            "SELECT id FROM monitor_sessions WHERE end_ms IS NOT NULL ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) lastId = c.getLong(0)
        }
        lastResult = if (lastId > 0L) SleepScorer.score(samplesFor(lastId)) else null
    }

    fun startSession(): Long {
        if (activeSessionId > 0L) return activeSessionId
        val values = ContentValues().apply { put("start_ms", System.currentTimeMillis()) }
        writableDatabase.insert("monitor_sessions", null, values)
        refresh()
        return activeSessionId
    }

    fun addSample(sample: SleepSample) {
        val sessionId = activeSessionId
        if (sessionId <= 0L) return
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("ts_ms", sample.tsMs)
            put("lux", sample.lux)
            put("covered", if (sample.covered) 1 else 0)
            put("noise_db", sample.noiseDb)
            put("noise_events", sample.noiseEvents)
            put("movement", sample.movement)
        }
        writableDatabase.insert("samples", null, values)
    }

    fun endSession() {
        val values = ContentValues().apply { put("end_ms", System.currentTimeMillis()) }
        writableDatabase.update("monitor_sessions", values, "end_ms IS NULL", null)
        refresh()
    }

    fun samplesFor(sessionId: Long): List<SleepSample> {
        val list = ArrayList<SleepSample>()
        readableDatabase.rawQuery(
            "SELECT ts_ms, lux, covered, noise_db, noise_events, movement " +
                    "FROM samples WHERE session_id = ? ORDER BY ts_ms ASC",
            arrayOf(sessionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SleepSample(
                        tsMs = c.getLong(0),
                        lux = c.getFloat(1),
                        covered = c.getInt(2) == 1,
                        noiseDb = c.getFloat(3),
                        noiseEvents = c.getInt(4),
                        movement = c.getFloat(5)
                    )
                )
            }
        }
        return list
    }

    companion object {
        @Volatile
        private var instance: SleepQualityStore? = null

        fun get(context: Context): SleepQualityStore =
            instance ?: synchronized(this) {
                instance ?: SleepQualityStore(context.applicationContext).also { instance = it }
            }
    }
}