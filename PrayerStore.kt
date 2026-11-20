package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

val PRAYER_NAMES = listOf("Fajr", "Johr", "Asr", "Magrib", "Esha")
const val TAHAJJUD_SLOT = 5

// slot 0..4 = Fajr, Johr, Asr, Magrib, Esha (each one is ticked on its own), 5 = Tahajjud
data class PrayerEntry(val id: Long, val slot: Int, val tsMs: Long)

class PrayerStore(context: Context) : SQLiteOpenHelper(context, "prayer.db", null, 1) {

    val todayEntries = mutableStateListOf<PrayerEntry>()
    var count by mutableIntStateOf(0)           // the five daily prayers done today
        private set
    var tahajjudDone by mutableStateOf(false)
        private set

    init {
        ResetTimes.init(context)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE prayer_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "day TEXT NOT NULL, " +
                    "slot INTEGER NOT NULL, " +
                    "ts_ms INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun today(): String = ResetTimes.dayNow("prayer").toString()

    private fun refresh() {
        todayEntries.clear()
        readableDatabase.rawQuery(
            "SELECT id, slot, ts_ms FROM prayer_log WHERE day = ? ORDER BY slot ASC",
            arrayOf(today())
        ).use { c ->
            while (c.moveToNext()) todayEntries.add(PrayerEntry(c.getLong(0), c.getInt(1), c.getLong(2)))
        }
        count = todayEntries.count { it.slot < TAHAJJUD_SLOT }
        tahajjudDone = todayEntries.any { it.slot == TAHAJJUD_SLOT }
    }

    private fun insert(slot: Int) {
        val v = ContentValues().apply {
            put("day", today()); put("slot", slot); put("ts_ms", System.currentTimeMillis())
        }
        writableDatabase.insert("prayer_log", null, v)
        refresh()
    }

    // Ticks or unticks one of the five prayers (0 = Fajr ... 4 = Esha), in any order.
    // Tapping a ticked prayer again removes the tick.
    fun toggle(slot: Int) {
        if (slot !in 0 until TAHAJJUD_SLOT) return
        if (todayEntries.any { it.slot == slot }) {
            writableDatabase.delete("prayer_log", "day = ? AND slot = ?", arrayOf(today(), slot.toString()))
            refresh()
        } else {
            insert(slot)
        }
    }

    fun addTahajjud() { if (!tahajjudDone) insert(TAHAJJUD_SLOT) }

    fun removeTahajjud() {
        writableDatabase.delete("prayer_log", "day = ? AND slot = ?", arrayOf(today(), TAHAJJUD_SLOT.toString()))
        refresh()
    }

    // ---- stored history, used by the reports ----

    fun dayEntries(day: LocalDate): List<PrayerEntry> {
        val list = ArrayList<PrayerEntry>()
        readableDatabase.rawQuery(
            "SELECT id, slot, ts_ms FROM prayer_log WHERE day = ? ORDER BY slot ASC",
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(PrayerEntry(c.getLong(0), c.getInt(1), c.getLong(2)))
        }
        return list
    }

    // Number of the five prayers per day (only days with prayers)
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        readableDatabase.rawQuery(
            "SELECT day, COUNT(*) FROM prayer_log WHERE slot < ? AND day >= ? AND day <= ? GROUP BY day",
            arrayOf(TAHAJJUD_SLOT.toString(), start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out[LocalDate.parse(c.getString(0))] = c.getInt(1)
        }
        return out
    }
}