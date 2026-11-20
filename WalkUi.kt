package com.example.fittrack

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.cos

private val WGreen = Color(0xFF81C784)
private val WMuted = Color(0xFF9AA0AA)

fun clockHms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

fun paceText(distanceM: Double, ms: Long): String {
    if (distanceM < 20.0) return "—"
    val minPerKm = ms / 60000.0 / (distanceM / 1000.0)
    val m = minPerKm.toInt()
    return "%d:%02d /km".format(m, ((minPerKm - m) * 60).toInt())
}

// The route drawn from its GPS points (no map needed)
@Composable
fun RouteView(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFF22262E), RoundedCornerShape(14.dp))) {
        if (points.size < 2) return@Canvas
        val lat0 = points.map { it.first }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = points.map { it.second * k }
        val ys = points.map { -it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 20f
        val w = (maxX - minX).coerceAtLeast(1e-6); val h = (maxY - minY).coerceAtLeast(1e-6)
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h).toFloat()
        val offX = (size.width - (w * scale).toFloat()) / 2f
        val offY = (size.height - (h * scale).toFloat()) / 2f
        fun pt(i: Int) = Offset(offX + ((xs[i] - minX) * scale).toFloat(), offY + ((ys[i] - minY) * scale).toFloat())
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until points.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, WGreen, style = Stroke(width = 6f, cap = StrokeCap.Round))
        drawCircle(Color(0xFF00E5A0), 10f, pt(0))
        drawCircle(Color(0xFFE57373), 10f, pt(points.lastIndex))
    }
}

// "Start" button logic: asks for location permission, then begins recording
@Composable
fun rememberWalkStarter(background: Boolean): () -> Unit {
    val context = LocalContext.current
    val bg by rememberUpdatedState(background)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (!WalkTracker.start(context, "Walk", bg))
                Toast.makeText(context, "Could not start GPS. Turn on location.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Location permission is needed to track distance and route", Toast.LENGTH_LONG).show()
        }
    }
    return {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (ok) {
            if (!WalkTracker.start(context, "Walk", bg))
                Toast.makeText(context, "Could not start GPS. Turn on location.", Toast.LENGTH_LONG).show()
        } else {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier.background(Color(0xFF22262E), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, color = WMuted, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

// Shown while a walk / run is being recorded. End saves it into the workout reports.
@Composable
fun WalkDialog(workouts: WorkoutStore, health: HealthStore) {
    val context = LocalContext.current
    val view = LocalView.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        // With the background service on, the screen may turn off to save battery
        view.keepScreenOn = !WalkTracker.inBackground
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val elapsed = now - WalkTracker.startMs
    val km = WalkTracker.distanceM / 1000.0
    val avg = if (elapsed > 0) km / (elapsed / 3600000.0) else 0.0

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F1115)) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Recording ${WalkTracker.type.lowercase()}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Walk", "Run").forEach { t ->
                        FilterChip(selected = WalkTracker.type == t, onClick = { WalkTracker.type = t }, label = { Text(t) })
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (WalkTracker.hasFix) "GPS ready" else "Searching GPS…",
                        color = if (WalkTracker.hasFix) WGreen else Color(0xFFFFB74D),
                        fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                Text(clockHms(elapsed), color = WGreen, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Distance", "%.2f km".format(km), Modifier.weight(1f))
                    Stat("Speed", "%.1f km/h".format(WalkTracker.speedKmh), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Avg speed", "%.1f km/h".format(avg), Modifier.weight(1f))
                    Stat("Pace", paceText(WalkTracker.distanceM, elapsed), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Max speed", "%.1f km/h".format(WalkTracker.maxKmh), Modifier.weight(1f))
                    Stat("Outside temp", WalkTracker.tempC?.let { "%.0f °C".format(it) } ?: "n/a", Modifier.weight(1f))
                }
                Text("Route", color = WMuted, fontSize = 12.sp)
                RouteView(WalkTracker.points.toList(), Modifier.fillMaxWidth().weight(1f))
                Button(
                    onClick = {
                        val r = WalkTracker.stop()
                        val minutes = ((r.endMs - r.startMs) / 60000).toInt()
                        if (minutes < 1) {
                            Toast.makeText(context, "Too short, not saved", Toast.LENGTH_SHORT).show()
                        } else {
                            val kg = health.weights.lastOrNull()?.value ?: 65f
                            val perKm = if (r.type == "Run") 1.0 else 0.53
                            val kcal = (kg * (r.distanceM / 1000.0) * perKm).toInt()
                                .coerceAtLeast(minutes * (if (r.type == "Run") 8 else 3))
                            workouts.addTracked(
                                r.type, minutes, kcal, r.distanceM, r.maxKmh, r.tempC, r.route, r.startMs, r.endMs
                            )
                            Toast.makeText(context, "Saved to workout reports", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373), contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("End", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// Details of recorded walks and runs for one day (shown when a workout report bar is tapped)
@Composable
fun WorkoutTrackDetails(workouts: List<Workout>) {
    workouts.filter { it.tracked }.forEach { w ->
        Spacer(Modifier.height(4.dp))
        Text(
            "${w.type} · %.2f km · %.1f km/h avg · %.1f km/h max".format(w.distanceM / 1000.0, w.avgKmh, w.maxKmh) +
                    (w.tempC?.let { " · %.0f °C".format(it) } ?: ""),
            color = Color(0xFFD0D3D9), fontSize = 12.sp
        )
        val pts = decodeRoute(w.route)
        if (pts.size >= 2) RouteView(pts, Modifier.fillMaxWidth().height(150.dp))
    }
}
