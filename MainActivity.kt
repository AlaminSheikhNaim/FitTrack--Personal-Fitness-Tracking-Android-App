package com.example.fittrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var tracker: StepTracker
    private lateinit var water: WaterStore
    private lateinit var food: FoodStore
    private lateinit var workouts: WorkoutStore
    private lateinit var sleep: SleepStore
    private lateinit var health: HealthStore
    private lateinit var settings: AppSettings
    private var shownDay: LocalDate = LocalDate.now()
    private var shownSig: String = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) tracker.start()
        askNotificationsOnce()
    }

    // Needed on Android 13 and newer for the morning sleep notification
    private var askedNotifications = false
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun askNotificationsOnce() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ResetTimes.init(this)
        shownDay = LocalDate.now()
        shownSig = ResetTimes.signature()
        tracker = StepTracker(this)
        water = WaterStore(this)
        food = FoodStore(this)
        workouts = WorkoutStore(this)
        sleep = SleepStore(this)
        health = HealthStore(this)
        settings = AppSettings(this)
        MorningCheck.ensureScheduled(this)   // the morning sleep notification
        MentalCheck.ensureScheduled(this)    // the 8:30 pm mental health check-in
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val view = androidx.compose.ui.platform.LocalView.current
                val light = settings.lightMode
                SideEffect {
                    val c = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    c.isAppearanceLightStatusBars = light
                    c.isAppearanceLightNavigationBars = light
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (light) Color(0xFFF1F3F6) else Color(0xFF0F1115)
                ) {
                    // At 12:00 am the screen is rebuilt, so every counter starts again from 0
                    AppRoot(
                        tracker, water, food, workouts, sleep, health, settings,
                        openMental = intent?.getBooleanExtra(MentalCheck.EXTRA_OPEN, false) == true,
                        onNewDay = { recreate() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MentalCheck.EXTRA_OPEN, false)) recreate()
    }

    override fun onResume() {
        super.onResume()
        if (LocalDate.now() != shownDay || ResetTimes.signature() != shownSig) {
            recreate()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            tracker.start()
            askNotificationsOnce()
        } else {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    override fun onPause() {
        super.onPause()
        tracker.stop()
    }
}