package com.example.fittrack

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// The time of day each card starts again from zero. Chosen in Settings > Reset Schedule.
object ResetTimes {
    // key, title, what restarts
    val ITEMS = listOf(
        Triple("steps", "Steps", "Step count starts again from 0"),
        Triple("water", "Water intake", "Today's glasses start again from 0"),
        Triple("food", "Calories", "Today's meals start again from 0"),
        Triple("workout", "Workout", "Today's workout minutes start again from 0"),
        Triple("sleep", "Sleep", "Today's sleep total starts again from 0"),
        Triple("eye", "Eye health", "Screen time starts counting again from 0"),
        Triple("prayer", "Prayer", "Today's prayers start again from 0"),
        Triple("mental", "Mental health", "Today's mood check-in starts again")
    )

    private var prefs: SharedPreferences? = null
    var version by mutableIntStateOf(0)
        private set

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
    }

    // Minutes after 12:00 am. Workout and eye health start at 5:00 am, the rest at 12:00 am.
    fun default(key: String) = if (key == "workout" || key == "eye" || key == "prayer") 300 else 0

    fun minutes(key: String): Int {
        version   // lets screens redraw when a time changes
        return prefs?.getInt(key, default(key)) ?: default(key)
    }

    fun set(key: String, minutes: Int) {
        prefs?.edit()?.putInt(key, minutes)?.apply()
        version += 1
    }

    // The "today" of a card: before its reset time it is still yesterday
    fun dayNow(key: String): LocalDate =
        LocalDateTime.now().minusMinutes(minutes(key).toLong()).toLocalDate()

    // When the card's current day began (milliseconds)
    fun startMs(key: String): Long =
        dayNow(key).atStartOfDay().plusMinutes(minutes(key).toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Changes when any card passes its reset time
    fun signature(): String = ITEMS.joinToString("|") { dayNow(it.first).toString() }

    fun label(min: Int): String {
        val h = min / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, min % 60, if (h < 12) "AM" else "PM")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetScheduleDetail(onBack: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }

    DetailScreen("Reset Schedule", onBack) {
        Text(
            "Choose the time each card starts again from zero. Tap a card to change its time.",
            color = Color(0xFF9AA0AA), fontSize = 13.sp
        )
        ResetTimes.ITEMS.forEach { (key, title, note) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1A1D23))
                    .clickable { editing = key }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(note, color = Color(0xFF9AA0AA), fontSize = 12.sp)
                }
                Text(
                    ResetTimes.label(ResetTimes.minutes(key)),
                    color = Color(0xFF00E5A0), fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }

    editing?.let { key ->
        val current = ResetTimes.minutes(key)
        val state = rememberTimePickerState(
            initialHour = current / 60, initialMinute = current % 60, is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Reset time · " + ResetTimes.ITEMS.first { it.first == key }.second) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    ResetTimes.set(key, state.hour * 60 + state.minute)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        ResetTimes.set(key, ResetTimes.default(key))
                        editing = null
                    }) { Text("Default") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }
}