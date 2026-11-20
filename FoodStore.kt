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

data class Meal(val id: Long, val name: String, val calories: Int)

class FoodStore(context: Context) : SQLiteOpenHelper(context, "fittrack.db", null, 1) {

    var caloriesToday by mutableIntStateOf(0)
        private set
    val mealsToday = mutableStateListOf<Meal>()

    init {
        ResetTimes.init(context)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE meals (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "day TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "calories INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun refresh() {
        val today = ResetTimes.dayNow("food").toString()
        mealsToday.clear()
        var total = 0
        readableDatabase.rawQuery(
            "SELECT id, name, calories FROM meals WHERE day = ? ORDER BY id DESC",
            arrayOf(today)
        ).use { c ->
            while (c.moveToNext()) {
                val meal = Meal(c.getLong(0), c.getString(1), c.getInt(2))
                mealsToday.add(meal)
                total += meal.calories
            }
        }
        caloriesToday = total
    }

    fun add(name: String, calories: Int) {
        val values = ContentValues().apply {
            put("day", ResetTimes.dayNow("food").toString())
            put("name", name)
            put("calories", calories)
        }
        writableDatabase.insert("meals", null, values)
        refresh()
    }

    // Calories per day between two days (only days with meals)
    fun totalsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        readableDatabase.rawQuery(
            "SELECT day, SUM(calories) FROM meals WHERE day >= ? AND day <= ? GROUP BY day",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out[LocalDate.parse(c.getString(0))] = c.getInt(1)
        }
        return out
    }

    fun mealsForDay(day: LocalDate): List<Meal> {
        val list = ArrayList<Meal>()
        readableDatabase.rawQuery(
            "SELECT id, name, calories FROM meals WHERE day = ? ORDER BY id ASC",
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(Meal(c.getLong(0), c.getString(1), c.getInt(2)))
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
        refresh()
    }
}