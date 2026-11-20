package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.ZoneId

object SleepType {
    const val NIGHT = "NIGHT"
    const val NAP = "NAP"
}

object SleepSource {
    const val STOPWATCH = "STOPWATCH"
    const val MANUAL = "MANUAL"
    const val ESTIMATED = "ESTIMATED"
    const val EDITED = "EDITED"
}

data class SleepSession(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val type: String,
    val source: String
)

class SleepStore(context: Context) : SQLiteOpenHelper(context, "sleep.db", null, 3) {

    var minutesToday by mutableIntStateOf(0)
        private set
    var nightMinutesToday by mutableIntStateOf(0)
        private set
    var napMinutesToday by mutableIntStateOf(0)
        private set
    var sleepingSinceMs by mutableLongStateOf(0L)   // 0 means the stopwatch is not running
        private set
    val sessionsToday = mutableStateListOf<SleepSession>()

    init {
        ResetTimes.init(context)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sleep (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "start_ms INTEGER NOT NULL, " +
                    "end_ms INTEGER, " +
                    "type TEXT NOT NULL DEFAULT 'NIGHT', " +
                    "source TEXT NOT NULL DEFAULT 'STOPWATCH')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sleep ADD COLUMN type TEXT NOT NULL DEFAULT 'NIGHT'")
            db.execSQL("ALTER TABLE sleep ADD COLUMN source TEXT NOT NULL DEFAULT 'STOPWATCH'")
        }
        if (oldVersion < 3) {
            // Version 2 called night sleep 'TOTAL'. Total Sleep is now its own card.
            db.execSQL("UPDATE sleep SET type = 'NIGHT' WHERE type = 'TOTAL'")
        }
    }

    private fun refresh() {
        // A session counts toward the day you WOKE UP, so last night counts today
        val startOfDay = ResetTimes.startMs("sleep")

        sessionsToday.clear()
        var night = 0
        var nap = 0
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, type, source FROM sleep " +
                    "WHERE end_ms IS NOT NULL AND end_ms >= ? ORDER BY end_ms DESC",
            arrayOf(startOfDay.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val s = SleepSession(
                    c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)
                )
                sessionsToday.add(s)
                val minutes = ((s.endMs - s.startMs) / 60000).toInt()
                if (s.type == SleepType.NAP) nap += minutes else night += minutes
            }
        }
        nightMinutesToday = night
        napMinutesToday = nap
        minutesToday = night + nap

        readableDatabase.rawQuery(
            "SELECT start_ms FROM sleep WHERE end_ms IS NULL ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            sleepingSinceMs = if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    // Sleep sessions that ended on one day (a session counts toward the day you woke up)
    fun sessionsForDay(day: LocalDate): List<SleepSession> {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val list = ArrayList<SleepSession>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, type, source FROM sleep " +
                    "WHERE end_ms IS NOT NULL AND end_ms >= ? AND end_ms < ? ORDER BY end_ms ASC",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(SleepSession(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return list
    }

    // Minutes slept per day between two days (only days with sleep)
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val zone = ZoneId.systemDefault()
        val out = HashMap<LocalDate, Int>()
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        readableDatabase.rawQuery(
            "SELECT start_ms, end_ms FROM sleep WHERE end_ms IS NOT NULL AND end_ms >= ? AND end_ms < ?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(zone).toLocalDate()
                val min = ((c.getLong(1) - c.getLong(0)) / 60000).toInt()
                out[day] = (out[day] ?: 0) + min
            }
        }
        return out
    }

    // Stopwatch (night sleep only)
    fun startSleep() {
        if (sleepingSinceMs > 0L) return
        val values = ContentValues().apply {
            put("start_ms", System.currentTimeMillis())
            put("type", SleepType.NIGHT)
            put("source", SleepSource.STOPWATCH)
        }
        writableDatabase.insert("sleep", null, values)
        refresh()
    }

    fun wake() {
        val values = ContentValues().apply { put("end_ms", System.currentTimeMillis()) }
        writableDatabase.update("sleep", values, "end_ms IS NULL", null)
        refresh()
    }

    fun cancelSleep() {
        writableDatabase.delete("sleep", "end_ms IS NULL", null)
        refresh()
    }

    // Manual entry from the clock picker
    fun addManual(type: String, startMs: Long, endMs: Long) =
        addSession(type, startMs, endMs, SleepSource.MANUAL)

    // Any finished session, with its source (MANUAL, ESTIMATED or EDITED)
    fun addSession(type: String, startMs: Long, endMs: Long, source: String) {
        val values = ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("type", type)
            put("source", source)
        }
        writableDatabase.insert("sleep", null, values)
        refresh()
    }

    // True if night sleep is already recorded (or the stopwatch is running) for this time
    fun hasNightBetween(startMs: Long, endMs: Long): Boolean {
        if (sleepingSinceMs > 0L) return true
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sleep WHERE type = 'NIGHT' AND end_ms IS NOT NULL " +
                    "AND start_ms < ? AND end_ms > ?",
            arrayOf(endMs.toString(), startMs.toString())
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

    // Editing: an ESTIMATED session becomes EDITED, other sources keep their label
    fun update(id: Long, startMs: Long, endMs: Long) {
        writableDatabase.execSQL(
            "UPDATE sleep SET start_ms = ?, end_ms = ?, " +
                    "source = CASE WHEN source = 'ESTIMATED' THEN 'EDITED' ELSE source END " +
                    "WHERE id = ?",
            arrayOf<Any>(startMs, endMs, id)
        )
        refresh()
    }

    fun delete(id: Long) {
        writableDatabase.delete("sleep", "id = ?", arrayOf(id.toString()))
        refresh()
    }
}