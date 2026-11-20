package com.example.fittrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun hm(m: Int): String = if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
fun CalorieReportSection(food: FoodStore, settings: AppSettings) {
    PeriodReportCard(
        title = "Calorie report",
        periods = Period.values().toList(),
        refreshKey = Pair(food.caloriesToday, food.mealsToday.size),
        today = ResetTimes.dayNow("food"),
        totalsBetween = { s, e -> food.totalsBetween(s, e) },
        goalPerDay = settings.calorieGoal,
        format = { "$it kcal" },
        accent = Color(0xFFFFB74D),
        yearlyAverage = true,
        rating = { r -> hydrationLevel(foodScore((r * 1000).toInt(), 1000)) },
        dayDetail = { d -> food.mealsForDay(d).map { it.name to "${it.calories} kcal" } }
    )
}

@Composable
fun SleepReportSection(sleep: SleepStore, settings: AppSettings) {
    PeriodReportCard(
        title = "Sleep report",
        periods = Period.values().toList(),
        refreshKey = sleep.minutesToday,
        today = java.time.LocalDate.now(),
        totalsBetween = { s, e -> sleep.totalsBetween(s, e) },
        goalPerDay = settings.sleepGoalMin,
        format = { hm(it) },
        accent = Color(0xFF9575CD),
        yearlyAverage = true,
        rating = { r -> hydrationLevel(sleepScore((r * settings.sleepGoalMin).toInt(), settings.sleepGoalMin)) },
        dayDetail = { d ->
            sleep.sessionsForDay(d).map {
                val type = if (it.type == SleepType.NAP) "Nap" else "Night"
                "$type  ${clock(it.startMs)} – ${clock(it.endMs)}" to hm(((it.endMs - it.startMs) / 60000).toInt())
            }
        },
        dayExtra = { d -> SleepQualityForDay(d) }
    )
}
