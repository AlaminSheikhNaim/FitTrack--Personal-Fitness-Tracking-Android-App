package com.example.fittrack

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

// Millilitres below 1 litre, litres from 1 litre up: 750 ml, 1 L, 1.25 L, 1.5 L
fun formatWater(ml: Int): String {
    if (ml < 1000) return "$ml ml"
    val litres = String.format(Locale.US, "%.2f", ml / 1000.0).trimEnd('0').trimEnd('.')
    return "$litres L"
}

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // The user's own background photo (copied into the app, so it keeps working)
    val bgFile = File(appContext.filesDir, "user_background.jpg")
    var bgVersion by mutableIntStateOf(prefs.getInt("bg_version", 0))
        private set

    fun setBackground(uri: Uri) {
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                bgFile.outputStream().use { input.copyTo(it) }
            }
            bgVersion += 1
            prefs.edit().putInt("bg_version", bgVersion).apply()
        } catch (e: Exception) { }
    }

    fun clearBackground() {
        bgFile.delete()
        bgVersion += 1
        prefs.edit().putInt("bg_version", bgVersion).apply()
    }

    var stepGoal by mutableIntStateOf(prefs.getInt("step_goal", 10000))
        private set
    var waterGoalMl by mutableIntStateOf(prefs.getInt("water_goal_ml", 2500))
        private set
    var cupMl by mutableIntStateOf(prefs.getInt("cup_ml", 250))
        private set
    var calorieGoal by mutableIntStateOf(prefs.getInt("calorie_goal", 2200))
        private set
    var workoutGoalMin by mutableIntStateOf(prefs.getInt("workout_goal_min", 60))
        private set

    // Settings > Set Targets > Walk / run tracking: keep recording with the screen off
    var walkBackground by mutableStateOf(prefs.getBoolean("walk_background", true))
        private set

    fun enableWalkBackground(on: Boolean) {
        walkBackground = on
        prefs.edit().putBoolean("walk_background", on).apply()
    }

    // Settings > Appearance > Dark / Light
    var lightMode by mutableStateOf(prefs.getBoolean("light_mode", false))
        private set

    fun chooseLightMode(on: Boolean) {
        lightMode = on
        prefs.edit().putBoolean("light_mode", on).apply()
    }

    // Order of the dashboard cards (saved after you rearrange them)
    var dashOrder by mutableStateOf(
        (prefs.getString("dash_order", "") ?: "").split(",").filter { it.isNotBlank() }
    )
        private set

    fun saveDashOrder(list: List<String>) {
        dashOrder = list
        prefs.edit().putString("dash_order", list.joinToString(",")).apply()
    }

    var sleepGoalMin by mutableIntStateOf(prefs.getInt("sleep_goal_min", 480))
        private set

    // Settings > Prayer card > Enable: shows or hides the Prayer card on the dashboard.
    // On by default, so the card stays visible after an update. Saved prayers are kept either way.
    var prayerEnabled by mutableStateOf(prefs.getBoolean("prayer_enabled", true))
        private set

    fun enablePrayerCard(on: Boolean) {
        prayerEnabled = on
        prefs.edit().putBoolean("prayer_enabled", on).apply()
    }

    fun changeSleepGoal(delta: Int) {
        sleepGoalMin = (sleepGoalMin + delta).coerceIn(180, 840)
        prefs.edit().putInt("sleep_goal_min", sleepGoalMin).apply()
    }

    fun changeStepGoal(delta: Int) {
        stepGoal = (stepGoal + delta).coerceIn(1000, 50000)
        prefs.edit().putInt("step_goal", stepGoal).apply()
    }

    fun changeWaterGoal(delta: Int) {
        waterGoalMl = (waterGoalMl + delta).coerceIn(500, 6000)
        prefs.edit().putInt("water_goal_ml", waterGoalMl).apply()
    }

    fun changeCup(delta: Int) {
        cupMl = (cupMl + delta).coerceIn(100, 1000)
        prefs.edit().putInt("cup_ml", cupMl).apply()
    }

    fun changeCalorieGoal(delta: Int) {
        calorieGoal = (calorieGoal + delta).coerceIn(800, 6000)
        prefs.edit().putInt("calorie_goal", calorieGoal).apply()
    }

    fun changeWorkoutGoal(delta: Int) {
        workoutGoalMin = (workoutGoalMin + delta).coerceIn(10, 300)
        prefs.edit().putInt("workout_goal_min", workoutGoalMin).apply()
    }
}