package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.util.Locale

object MeasureType {
    const val WEIGHT = "WEIGHT"
    const val HEIGHT = "HEIGHT"
}

data class Measurement(val id: Long, val tsMs: Long, val value: Float)

data class Vaccine(val id: Long, val name: String, val day: String)

// 72.0 -> "72", 72.5 -> "72.5"
fun cleanNum(v: Float): String {
    val s = String.format(Locale.US, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

// BMI = weight in kg divided by height in metres squared
fun bmiOf(weightKg: Float, heightCm: Float): Float {
    val m = heightCm / 100f
    return weightKg / (m * m)
}

fun bmiLabel(bmi: Float): String = when {
    bmi < 18.5f -> "Underweight"
    bmi < 25f -> "Normal"
    bmi < 30f -> "Overweight"
    else -> "Obese"
}

class HealthStore(context: Context) : SQLiteOpenHelper(context, "health.db", null, 1) {

    val weights = mutableStateListOf<Measurement>()   // oldest first
    val heights = mutableStateListOf<Measurement>()   // oldest first
    val vaccines = mutableStateListOf<Vaccine>()      // newest first

    init {
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE measurement (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "type TEXT NOT NULL, " +
                    "ts_ms INTEGER NOT NULL, " +
                    "value REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE vaccine (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "day TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun refresh() {
        weights.clear()
        heights.clear()
        vaccines.clear()
        readableDatabase.rawQuery(
            "SELECT id, type, ts_ms, value FROM measurement ORDER BY ts_ms ASC, id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val m = Measurement(c.getLong(0), c.getLong(2), c.getFloat(3))
                if (c.getString(1) == MeasureType.WEIGHT) weights.add(m) else heights.add(m)
            }
        }
        readableDatabase.rawQuery(
            "SELECT id, name, day FROM vaccine ORDER BY day DESC, id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                vaccines.add(Vaccine(c.getLong(0), c.getString(1), c.getString(2)))
            }
        }
    }

    fun addMeasurement(type: String, tsMs: Long, value: Float) {
        val v = ContentValues().apply {
            put("type", type)
            put("ts_ms", tsMs)
            put("value", value)
        }
        writableDatabase.insert("measurement", null, v)
        refresh()
    }

    fun deleteMeasurement(id: Long) {
        writableDatabase.delete("measurement", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    fun addVaccine(name: String, day: LocalDate) {
        val v = ContentValues().apply {
            put("name", name)
            put("day", day.toString())
        }
        writableDatabase.insert("vaccine", null, v)
        refresh()
    }

    fun deleteVaccine(id: Long) {
        writableDatabase.delete("vaccine", "id = ?", arrayOf(id.toString()))
        refresh()
    }
}