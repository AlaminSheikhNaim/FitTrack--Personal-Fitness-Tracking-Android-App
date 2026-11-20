package com.example.fittrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt

private val Card = Color(0xFF1A1D23)
private val Muted = Color(0xFF9AA0AA)
private val Body = Color(0xFFD0D3D9)

private class Rated(val name: String, val score: Int, val detail: String)

private fun stars(score: Int): String {
    val n = if (score <= 0) 0 else (score / 20f).roundToInt().coerceIn(1, 5)
    return "★".repeat(n) + "☆".repeat(5 - n)
}

// ---- scoring (0..100) ----
private fun stepScore(steps: Int, goal: Int) =
    (minOf(steps.toFloat() / goal.coerceAtLeast(1), 1f) * 100).roundToInt()

// Best when you eat 85%-110% of your calorie goal; too little or too much lowers it
internal fun foodScore(kcal: Int, goal: Int): Int {
    if (kcal <= 0) return 0
    val r = kcal.toFloat() / goal.coerceAtLeast(1)
    return when {
        r < 0.85f -> (r / 0.85f * 100).roundToInt()
        r <= 1.10f -> 100
        else -> (100 - (r - 1.10f) * 200).roundToInt().coerceAtLeast(0)
    }
}

// Rated against your sleep target: full score from 12% under to 12% over the target.
// Less sleep lowers it in proportion; much more than the target lowers it slowly.
internal fun sleepScore(minutes: Int, goalMin: Int): Int {
    if (minutes <= 0) return 0
    val goal = goalMin.coerceAtLeast(1)
    val low = goal * 0.88f
    val high = goal * 1.12f
    return when {
        minutes < low -> (minutes / low * 100).roundToInt()
        minutes <= high -> 100
        else -> (100 - (minutes - high) / 60f * 20f).roundToInt().coerceAtLeast(0)
    }
}

@Composable
fun ActivityRatingCard(
    tracker: StepTracker,
    water: WaterStore,
    food: FoodStore,
    workouts: WorkoutStore,
    sleep: SleepStore,
    settings: AppSettings
) {
    val items = listOf(
        Rated("Steps", stepScore(tracker.stepsToday, settings.stepGoal),
            "%,d / %,d".format(tracker.stepsToday, settings.stepGoal)),
        Rated("Water", hydrationRating(water.entriesToday.toList(), settings.waterGoalMl).score,
            "${formatWater(water.mlToday)} / ${formatWater(settings.waterGoalMl)}"),
        Rated("Food", foodScore(food.caloriesToday, settings.calorieGoal),
            "${food.caloriesToday} / ${settings.calorieGoal} kcal"),
        Rated("Workout", stepScore(workouts.minutesToday, settings.workoutGoalMin),
            "${workouts.minutesToday} / ${settings.workoutGoalMin} min"),
        Rated("Sleep", sleepScore(sleep.minutesToday, settings.sleepGoalMin),
            "${sleep.minutesToday / 60}h ${sleep.minutesToday % 60}m / ${settings.sleepGoalMin / 60}h ${settings.sleepGoalMin % 60}m")
    )
    val overall = items.sumOf { it.score } / items.size
    val (overallLabel, overallColor) = hydrationLevel(overall)
    val weakest = items.minByOrNull { it.score }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Today's activity rating", color = Muted, fontSize = 14.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(overallLabel, color = overallColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("$overall / 100", color = Body, fontSize = 14.sp)
        }
        items.forEach { r ->
            val (label, color) = hydrationLevel(r.score)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(r.detail, color = Muted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stars(r.score), color = color, fontSize = 15.sp)
                    Text(if (r.score == 0) "Not logged" else label, color = color, fontSize = 11.sp)
                }
            }
        }
        if (weakest != null && weakest.score < 100) {
            Text("Focus next: ${weakest.name}", color = Muted, fontSize = 12.sp)
        }
    }
}

// Steps report: same style as the workout report, every bar is tappable
@Composable
fun StepsReportSection(tracker: StepTracker, settings: AppSettings) {
    PeriodReportCard(
        title = "Steps report",
        periods = Period.values().toList(),
        refreshKey = tracker.stepsToday,
        today = ResetTimes.dayNow("steps"),
        totalsBetween = { s, e -> tracker.totalsBetween(s, e) },
        goalPerDay = settings.stepGoal,
        format = { "%,d steps".format(it) },
        accent = Color(0xFF00E5A0),
        yearlyAverage = true,
        rating = ::hydrationFromRatio,
        dayDetail = { d ->
            val steps = tracker.totalsBetween(d, d)[d] ?: 0
            listOf(
                "Distance" to "%.2f km".format(steps * 0.00075),
                "Calories burned" to "${(steps * 0.04).toInt()} kcal"
            )
        }
    )
}
