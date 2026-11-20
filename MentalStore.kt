package com.example.fittrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

// mood 1..5 : 1 very sad, 2 sad, 3 okay, 4 happy, 5 very happy
data class MoodEntry(val day: LocalDate, val mood: Int, val reasons: List<String>, val tsMs: Long)

val MOOD_LABELS = listOf("Very sad", "Sad", "Okay", "Happy", "Very happy")
val MOOD_FACES = listOf("😭", "😟", "😐", "🙂", "😄")

val HAPPY_REASONS = listOf(
    "Family & friends", "Good sleep", "Work/study went well", "Exercise", "Prayer & peace",
    "Good food", "Hobby / free time", "Good news", "Outing / nature"
)
val LOW_REASONS = listOf(
    "Stress (work/study)", "Poor sleep", "Family problem", "Loneliness", "Health worry",
    "Money worry", "Relationship", "Tired / burnt out", "Overthinking", "Missed prayers", "Unhealthy diet"
)
val OKAY_REASONS = listOf("Routine day", "Busy", "Tired", "Nothing special", "Mixed feelings", "Waiting for something")

fun reasonsFor(mood: Int): List<String> = when {
    mood >= 4 -> HAPPY_REASONS
    mood <= 2 -> LOW_REASONS
    else -> OKAY_REASONS
}

class MentalStore(context: Context) : SQLiteOpenHelper(context, "mental.db", null, 1) {

    var todayMood by mutableIntStateOf(0)       // 0 = not answered yet today
        private set
    var todayReasons by mutableStateOf<List<String>>(emptyList())
        private set
    var version by mutableIntStateOf(0)         // lets reports redraw after a save
        private set

    init {
        ResetTimes.init(context)
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE mood_log (day TEXT PRIMARY KEY, mood INTEGER NOT NULL, " +
                    "reasons TEXT NOT NULL, ts_ms INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // The mental health "day" follows its reset time in Settings > Reset Schedule
    fun today(): LocalDate = ResetTimes.dayNow("mental")

    fun refresh() {
        val e = entry(today())
        todayMood = e?.mood ?: 0
        todayReasons = e?.reasons ?: emptyList()
        version += 1
    }

    // One answer per day; answering again replaces it
    fun save(mood: Int, reasons: List<String>) {
        val v = ContentValues().apply {
            put("day", today().toString()); put("mood", mood.coerceIn(1, 5))
            put("reasons", reasons.joinToString("|")); put("ts_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("mood_log", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        refresh()
    }

    private fun read(c: android.database.Cursor) = MoodEntry(
        LocalDate.parse(c.getString(0)), c.getInt(1),
        c.getString(2).split("|").filter { it.isNotBlank() }, c.getLong(3)
    )

    fun entry(day: LocalDate): MoodEntry? =
        readableDatabase.rawQuery(
            "SELECT day, mood, reasons, ts_ms FROM mood_log WHERE day = ?", arrayOf(day.toString())
        ).use { c -> if (c.moveToFirst()) read(c) else null }

    fun between(start: LocalDate, end: LocalDate): Map<LocalDate, MoodEntry> {
        val out = HashMap<LocalDate, MoodEntry>()
        readableDatabase.rawQuery(
            "SELECT day, mood, reasons, ts_ms FROM mood_log WHERE day >= ? AND day <= ?",
            arrayOf(start.toString(), end.toString())
        ).use { c -> while (c.moveToNext()) read(c).let { out[it.day] = it } }
        return out
    }
}
