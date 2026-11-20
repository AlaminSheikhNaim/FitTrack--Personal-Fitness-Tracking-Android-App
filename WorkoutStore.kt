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
import java.time.LocalDateTime

// The workout day starts at 5:00 am: at 4:59 am it is still yesterday's workout day
fun workoutDayNow(): LocalDate = ResetTimes.dayNow("workout")

// A walk or run recorded with Start / End also has distance, speed, temperature and route
data class Workout(
    val id: Long, val type: String, val minutes: Int, val calories: Int,
    val distanceM: Double = 0.0, val maxKmh: Double = 0.0, val tempC: Double? = null,
    val route: String = "", val startMs: Long = 0L, val endMs: Long = 0L
) {
    val tracked get() = distanceM > 0.0 || route.isNotEmpty()
    val avgKmh get() = if (minutes > 0) distanceM / 1000.0 / (minutes / 60.0) else 0.0
}

private const val WCOLS = "id, type, minutes, calories, distance_m, max_kmh, temp_c, route, start_ms, end_ms"

private fun readWorkout(c: android.database.Cursor) = Workout(
    c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
    c.getDouble(4), c.getDouble(5), c.getDouble(6).let { if (it < -900) null else it },
    c.getString(7) ?: "", c.getLong(8), c.getLong(9)
)

class WorkoutStore(context: Context) : SQLiteOpenHelper(context, "workouts.db", null, 2) {

    var minutesToday by mutableIntStateOf(0)
        private set
    val workoutsToday = mutableStateListOf<Workout>()
    private var shownDay: LocalDate = workoutDayNow()

    init {
        ResetTimes.init(context)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE workouts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "day TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "minutes INTEGER NOT NULL, " +
                    "calories INTEGER NOT NULL, " +
                    "distance_m REAL NOT NULL DEFAULT 0, " +
                    "max_kmh REAL NOT NULL DEFAULT 0, " +
                    "temp_c REAL NOT NULL DEFAULT -999, " +
                    "route TEXT NOT NULL DEFAULT '', " +
                    "start_ms INTEGER NOT NULL DEFAULT 0, " +
                    "end_ms INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN distance_m REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workouts ADD COLUMN max_kmh REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workouts ADD COLUMN temp_c REAL NOT NULL DEFAULT -999")
            db.execSQL("ALTER TABLE workouts ADD COLUMN route TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workouts ADD COLUMN start_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workouts ADD COLUMN end_ms INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun refresh() {
        shownDay = workoutDayNow()
        val today = shownDay.toString()
        workoutsToday.clear()
        var total = 0
        readableDatabase.rawQuery(
            "SELECT $WCOLS FROM workouts WHERE day = ? ORDER BY id DESC",
            arrayOf(today)
        ).use { c ->
            while (c.moveToNext()) {
                val w = readWorkout(c)
                workoutsToday.add(w)
                total += w.minutes
            }
        }
        minutesToday = total
    }

    fun add(type: String, minutes: Int, calories: Int) {
        val values = ContentValues().apply {
            put("day", workoutDayNow().toString())
            put("type", type)
            put("minutes", minutes)
            put("calories", calories)
        }
        writableDatabase.insert("workouts", null, values)
        refresh()
    }

    // A walk or run recorded with Start / End. It counts in the workout minutes and reports.
    fun addTracked(
        type: String, minutes: Int, calories: Int, distanceM: Double, maxKmh: Double,
        tempC: Double?, route: String, startMs: Long, endMs: Long
    ) {
        val values = ContentValues().apply {
            put("day", workoutDayNow().toString())
            put("type", type); put("minutes", minutes); put("calories", calories)
            put("distance_m", distanceM); put("max_kmh", maxKmh)
            put("temp_c", tempC ?: -999.0); put("route", route)
            put("start_ms", startMs); put("end_ms", endMs)
        }
        writableDatabase.insert("workouts", null, values)
        refresh()
    }

    // Call now and then: at 5:00 am the list starts again from 0
    fun checkReset() {
        if (workoutDayNow() != shownDay) refresh()
    }

    // Minutes per workout day between two days. Only days with workouts are in the map.
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(minutes) FROM workouts WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out[LocalDate.parse(c.getString(0))] = c.getInt(1)
        }
        return out
    }

    // Every workout of one day (shown when a report bar is tapped)
    fun workoutsForDay(day: LocalDate): List<Workout> {
        val list = ArrayList<Workout>()
        readableDatabase.rawQuery(
            "SELECT $WCOLS FROM workouts WHERE day = ? ORDER BY id ASC",
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(readWorkout(c))
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete("workouts", "id = ?", arrayOf(id.toString()))
        refresh()
    }
}