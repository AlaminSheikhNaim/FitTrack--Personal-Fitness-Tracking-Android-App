package com.example.fittrack

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class GoalsStore(context: Context) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    var steps by mutableIntStateOf(prefs.getInt("steps", 10000))
        private set
    var waterMl by mutableIntStateOf(prefs.getInt("water_ml", 2500))
        private set
    var calories by mutableIntStateOf(prefs.getInt("calories", 2200))
        private set
    var workoutMinutes by mutableIntStateOf(prefs.getInt("workout_min", 60))
        private set

    fun updateSteps(value: Int) {
        steps = value.coerceIn(1000, 100000)
        prefs.edit().putInt("steps", steps).apply()
    }

    fun updateWaterMl(value: Int) {
        waterMl = value.coerceIn(500, 10000)
        prefs.edit().putInt("water_ml", waterMl).apply()
    }

    fun updateCalories(value: Int) {
        calories = value.coerceIn(800, 10000)
        prefs.edit().putInt("calories", calories).apply()
    }

    fun updateWorkoutMinutes(value: Int) {
        workoutMinutes = value.coerceIn(5, 600)
        prefs.edit().putInt("workout_min", workoutMinutes).apply()
    }
}