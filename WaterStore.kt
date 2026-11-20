package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

const val GLASS_ML = 250

data class WaterEntry(val id: Long, val tsMs: Long, val ml: Int)

class WaterStore(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "water.db", null, 1) {

    var glassesToday by mutableIntStateOf(0)
        private set
    var mlToday by mutableIntStateOf(0)
        private set
    val entriesToday = mutableStateListOf<WaterEntry>()

    init {
        ResetTimes.init(appContext)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE water_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "day TEXT NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "ml INTEGER NOT NULL)"
        )
        // Carry over today's total from the old version, which stored a single number
        val old = appContext.getSharedPreferences("water", Context.MODE_PRIVATE)
        val today = ResetTimes.dayNow("water").toString()
        if (old.getString("day", null) == today) {
            val glasses = old.getInt("ml", 0) / GLASS_ML
            repeat(glasses) {
                val v = ContentValues().apply {
                    put("day", today)
                    put("ts_ms", System.currentTimeMillis())
                    put("ml", GLASS_ML)
                }
                db.insert("water_log", null, v)
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun refresh() {
        entriesToday.clear()
        var total = 0
        readableDatabase.rawQuery(
            "SELECT id, ts_ms, ml FROM water_log WHERE day = ? ORDER BY ts_ms DESC, id DESC",
            arrayOf(ResetTimes.dayNow("water").toString())
        ).use { c ->
            while (c.moveToNext()) {
                val e = WaterEntry(c.getLong(0), c.getLong(1), c.getInt(2))
                entriesToday.add(e)
                total += e.ml
            }
        }
        mlToday = total
        glassesToday = entriesToday.size
    }

    // Adds one glass of the given size (your cup size from Settings)
    fun addGlass(ml: Int) {
        val v = ContentValues().apply {
            put("day", ResetTimes.dayNow("water").toString())
            put("ts_ms", System.currentTimeMillis())
            put("ml", ml)
        }
        writableDatabase.insert("water_log", null, v)
        refresh()
    }

    // The "-1" button: removes the most recent glass of today
    fun removeLast() {
        writableDatabase.execSQL(
            "DELETE FROM water_log WHERE id = " +
                    "(SELECT id FROM water_log WHERE day = ? ORDER BY ts_ms DESC, id DESC LIMIT 1)",
            arrayOf<Any>(ResetTimes.dayNow("water").toString())
        )
        refresh()
    }

    fun delete(id: Long) {
        writableDatabase.delete("water_log", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    // Millilitres per day between two days. Only days with entries are in the map.
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(ml) FROM water_log WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out[LocalDate.parse(c.getString(0))] = c.getInt(1)
        }
        return out
    }

    // Every glass of one day (used to rate how the water was spread through the day)
    fun entriesForDay(day: LocalDate): List<WaterEntry> {
        val list = ArrayList<WaterEntry>()
        readableDatabase.rawQuery(
            "SELECT id, ts_ms, ml FROM water_log WHERE day = ? ORDER BY ts_ms ASC",
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(WaterEntry(c.getLong(0), c.getLong(1), c.getInt(2)))
        }
        return list
    }

    // Millilitres per day for the last N days, oldest first.
    fun dailyTotals(days: Int): List<Pair<LocalDate, Int>> {
        val start = ResetTimes.dayNow("water").minusDays((days - 1).toLong())
        val totals = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(ml) FROM water_log WHERE day >= ? GROUP BY day",
            arrayOf(start.toString())
        ).use { c ->
            while (c.moveToNext()) {
                totals[c.getString(0)] = c.getInt(1)
            }
        }
        return (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            Pair(d, totals[d.toString()] ?: 0)
        }
    }
}