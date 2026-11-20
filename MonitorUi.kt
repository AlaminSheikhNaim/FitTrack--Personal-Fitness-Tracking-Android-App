package com.example.fittrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Teal = Color(0xFF4DB6AC)
private val Muted = Color(0xFF9AA0AA)
private val Body = Color(0xFFD0D3D9)

private fun clockText(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

private fun durationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun qualityLabel(score: Int): String = when {
    score < 0 -> "Not rated"
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 50 -> "Fair"
    else -> "Poor"
}

private fun tierColor(score: Int): Color = when {
    score < 0 -> Color(0xFF5A6070)
    score >= 85 -> Color(0xFF00E5A0)
    score >= 70 -> Color(0xFF81C784)
    score >= 50 -> Color(0xFFFFB74D)
    else -> Color(0xFFEF9A9A)
}

// Green while nothing disturbs sleep, then amber, then red and deeper red as it gets worse
private fun disturbColor(disturbance: Int): Color {
    val d = disturbance.coerceIn(0, 100)
    val green = Color(0xFF00E5A0)
    val amber = Color(0xFFFFB74D)
    val red = Color(0xFFFF5252)
    val deepRed = Color(0xFFB71C1C)
    return when {
        d <= 15 -> green
        d <= 45 -> lerp(green, amber, (d - 15) / 30f)
        d <= 70 -> lerp(amber, red, (d - 45) / 25f)
        else -> lerp(red, deepRed, (d - 70) / 30f)
    }
}

private fun kindColor(kind: String): Color = when (kind) {
    "LIGHT" -> Color(0xFFFFD54F)
    "SOUND" -> Color(0xFF4FC3F7)
    "MOVEMENT" -> Color(0xFFB388FF)
    else -> Color(0xFFF48FB1)
}

private fun kindWord(kind: String): String = when (kind) {
    "LIGHT" -> "Light"
    "SOUND" -> "Sound"
    "MOVEMENT" -> "Movement"
    else -> "Phone"
}

private fun startMonitor(context: Context, test: Boolean) {
    val intent = Intent(context, SleepMonitorService::class.java)
        .setAction(SleepMonitorService.ACTION_START)
        .putExtra(SleepMonitorService.EXTRA_TEST, test)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopMonitor(context: Context) {
    context.startService(
        Intent(context, SleepMonitorService::class.java).setAction(SleepMonitorService.ACTION_STOP)
    )
}

private fun sensorSummary(context: Context): String {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    val accText = when {
        acc == null -> "none"
        acc.fifoMaxEventCount > 0 -> "batching OK (FIFO ${acc.fifoMaxEventCount})"
        else -> "no batching"
    }
    val lightText = if (light == null) "none"
    else "resolution ${light.resolution} lx, max ${light.maximumRange} lx"
    val proxText = if (prox == null) "none" else "range ${prox.maximumRange} cm"
    return "Accelerometer: $accText\nLight: $lightText\nProximity: $proxText"
}

private fun blockLine(b: MonitorBlock): String {
    val light = b.lightLux?.let { "%.1f lx".format(it) } ?: "n/a"
    val cover = if (b.covered) " (covered)" else ""
    val noise = if (b.noiseFloorDb != null && b.noisePeakDb != null) {
        "%.0f/%.0f dB".format(b.noiseFloorDb, b.noisePeakDb)
    } else {
        "mic n/a"
    }
    val move = b.motionMean?.let { "%.3f".format(it) } ?: "n/a"
    return "${clockText(b.tsMs)}  light $light$cover  noise $noise  events ${b.noiseEvents}  move $move"
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Teal else Color(0xFF2A2E36))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val color = tierColor(score)
    Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color(0xFF2A2E36), startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color, startAngle = -90f,
                sweepAngle = 360f * score.coerceIn(0, 100) / 100f,
                useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score < 0) "-" else "$score",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold
            )
            Text("of 100", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ScoreBar(label: String, score: Int) {
    // The report stores a score (100 = calm). The bar shows the opposite: how much it disturbed you
    val disturbance = if (score < 0) 0 else (100 - score).coerceIn(0, 100)
    val color = if (score < 0) Color(0xFF5A6070) else disturbColor(disturbance)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Body, fontSize = 13.sp)
            Text(
                if (score < 0) "not measured" else "$disturbance% disturbed",
                color = if (score < 0) Muted else color,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { if (score < 0) 0f else maxOf(disturbance / 100f, 0.03f) },
            color = color,
            trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------- The report card ----------

@Composable
private fun ReportTab(store: MonitorStore) {
    // 0 means "follow the newest report"; otherwise the night the person stepped back to
    var pickedId by remember { mutableLongStateOf(0L) }
    val list = store.reports
    val report = list.firstOrNull { it.sessionId == pickedId } ?: list.firstOrNull()

    if (report == null) {
        Text(
            "No sleep report yet. Start monitoring before you sleep and stop it " +
                    "when you wake up. Your report card appears here and is saved, " +
                    "so you can look back at earlier nights.",
            color = Body, fontSize = 13.sp
        )
        if (store.isActive) {
            Text(
                "Monitoring is running. The report is made when you tap Stop.",
                color = Teal, fontSize = 13.sp
            )
        }
        return
    }

    val day = LocalDate.parse(report.day)
    val today = LocalDate.now()
    val dayLabel = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("d MMM"))
    }

    // Step through the saved nights (newest first in the list)
    val position = list.indexOfFirst { it.sessionId == report.sessionId }
    if (list.size > 1) {
        val hasOlder = position < list.size - 1
        val hasNewer = position > 0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { pickedId = list[position + 1].sessionId },
                enabled = hasOlder
            ) { Text("‹ Older", color = if (hasOlder) Teal else Muted, fontSize = 12.sp) }
            Text("${position + 1} of ${list.size} saved nights", color = Muted, fontSize = 12.sp)
            TextButton(
                onClick = { pickedId = if (position == 1) 0L else list[position - 1].sessionId },
                enabled = hasNewer
            ) { Text("Newer ›", color = if (hasNewer) Teal else Muted, fontSize = 12.sp) }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoreRing(report.score)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Sleep report · $dayLabel", color = Muted, fontSize = 13.sp
            )
            Text(
                qualityLabel(report.score), color = tierColor(report.score),
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "${clockText(report.startMs)} - ${clockText(report.endMs)}",
                color = Body, fontSize = 13.sp
            )
            Text(
                "Monitored ${durationText(report.durationMin)}" +
                        if (report.score >= 0) "  ·  asleep about ${durationText(report.asleepMin)}" else "",
                color = Muted, fontSize = 12.sp
            )
            if (report.score >= 0 && report.latencyMin > 0) {
                Text(
                    "Fell asleep about ${report.latencyMin} min after starting",
                    color = Muted, fontSize = 12.sp
                )
            }
            if (report.test) {
                Text("TEST SESSION", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    Text(report.summary, color = Color.White, fontSize = 14.sp)

    if (report.score >= 0) {
        Text("What affected your sleep", color = Muted, fontSize = 13.sp)
        ScoreBar("Movement", report.movementScore)
        ScoreBar("Sound", report.soundScore)
        ScoreBar("Light", report.lightScore)
        ScoreBar("Phone use", report.interruptionScore)
    }

    Text("What disturbed your sleep", color = Muted, fontSize = 13.sp)
    if (report.disturbances.isEmpty()) {
        Text("Nothing significant was detected.", color = Body, fontSize = 13.sp)
    }
    report.disturbances.forEach { d ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(clockText(d.tsMs), color = Muted, fontSize = 12.sp, modifier = Modifier.width(62.dp))
            Text(
                kindWord(d.kind), color = kindColor(d.kind), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.width(66.dp)
            )
            Text(d.text, color = Body, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }

    if (report.tips.isNotEmpty()) {
        Text("Tips", color = Muted, fontSize = 13.sp)
        report.tips.split("\n").forEach { tip ->
            Text("•  $tip", color = Body, fontSize = 12.sp)
        }
    }

    TextButton(onClick = { store.reanalyze() }) {
        Text("Analyse again", color = Teal, fontSize = 12.sp)
    }
}

// ---------- The raw readings ----------

@Composable
private fun SensorDataTab(store: MonitorStore, sensorInfo: String) {
    if (store.sessionId == 0L) {
        Text("No readings yet. Start monitoring first.", color = Body, fontSize = 13.sp)
    } else {
        Text(
            "Latest session: ${store.blockCount} readings  ·  ${store.eventCount} screen on/off events",
            color = Body, fontSize = 13.sp
        )
        Text(
            "Each line: time, light, noise (quiet level / loudest, in dB), loud-sound count and movement",
            color = Muted, fontSize = 11.sp
        )
        store.recentBlocks.forEach { b ->
            Text(blockLine(b), color = Body, fontSize = 11.sp)
        }
    }
    Text("Sensors on this phone", color = Muted, fontSize = 12.sp)
    Text(sensorInfo, color = Muted, fontSize = 11.sp)
}

// ---------- The monitor card ----------

@Composable
fun MonitorSection() {
    val context = LocalContext.current
    val store = remember { MonitorStore.get(context) }
    var tab by remember { mutableStateOf("report") }
    var testMode by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val sensorInfo = remember { sensorSummary(context) }
    val permissions = remember {
        buildList<String> {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            micDenied = false
            startMonitor(context, testMode)
        } else {
            micDenied = true
        }
    }

    val active = store.isActive
    val lastActivity = if (store.lastBlockMs > 0L) store.lastBlockMs else store.startedAtMs
    val stale = active && store.blockCount > 0 &&
            now - lastActivity > store.intervalS * 3000L + 120_000L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Sleep Quality Monitor", color = Muted, fontSize = 14.sp)

        if (active) {
            Text(
                "Monitoring since ${clockText(store.startedAtMs)}  ·  ${store.blockCount} readings",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            if (store.blockCount == 0) {
                Text("Waiting for the first reading...", color = Muted, fontSize = 12.sp)
            }
            if (stale) {
                Text(
                    "No new data for a while. The system may have stopped monitoring. " +
                            "Check the battery settings below.",
                    color = Color(0xFFFF8A80), fontSize = 12.sp
                )
            }
            Button(
                onClick = { stopMonitor(context) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF9A9A),
                    contentColor = Color.Black
                )
            ) { Text("Stop monitoring") }
        } else {
            Text(
                "Put the phone on the bed, screen up, then start before you sleep.",
                color = Muted, fontSize = 13.sp
            )
            Button(
                onClick = { permissionLauncher.launch(permissions) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Teal,
                    contentColor = Color.Black
                )
            ) { Text("Start monitoring") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = testMode, onCheckedChange = { testMode = it })
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test mode (1-minute readings)", color = Body, fontSize = 13.sp)
            }
        }

        if (micDenied) {
            Text(
                "Microphone permission is required. Allow it in Settings > Apps > FitTrack > Permissions.",
                color = Color(0xFFFF8A80), fontSize = 12.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("Report", tab == "report") { tab = "report" }
            TabChip("Sensor Data", tab == "sensor") { tab = "sensor" }
        }

        if (tab == "report") {
            ReportTab(store)
        } else {
            SensorDataTab(store, sensorInfo)
        }

        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                // No settings screen available on this phone
            }
        }) { Text("Battery settings", color = Teal, fontSize = 12.sp) }
    }
}

// Shown inside the sleep graph when a night's bar is tapped: that night's quality report(s)
@Composable
fun SleepQualityForDay(day: LocalDate) {
    val context = LocalContext.current
    val store = remember { MonitorStore.get(context) }
    val list = store.reports.filter { it.day == day.toString() }
    if (list.isEmpty()) return
    list.forEach { report ->
        Spacer(modifier = Modifier.height(4.dp))
        Text("Sleep quality report", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScoreRing(report.score)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    qualityLabel(report.score), color = tierColor(report.score),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
                Text("${clockText(report.startMs)} - ${clockText(report.endMs)}", color = Body, fontSize = 12.sp)
                Text(
                    "Monitored ${durationText(report.durationMin)}" +
                            if (report.score >= 0) " · asleep about ${durationText(report.asleepMin)}" else "",
                    color = Muted, fontSize = 11.sp
                )
                if (report.test) {
                    Text("TEST SESSION", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(report.summary, color = Color.White, fontSize = 13.sp)
        if (report.score >= 0) {
            ScoreBar("Movement", report.movementScore)
            ScoreBar("Sound", report.soundScore)
            ScoreBar("Light", report.lightScore)
            ScoreBar("Phone use", report.interruptionScore)
        }
        if (report.disturbances.isNotEmpty()) {
            Text("What disturbed your sleep", color = Muted, fontSize = 12.sp)
            report.disturbances.forEach { d ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clockText(d.tsMs), color = Muted, fontSize = 11.sp, modifier = Modifier.width(58.dp))
                    Text(kindWord(d.kind), color = kindColor(d.kind), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                    Text(d.text, color = Body, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        if (report.tips.isNotEmpty()) {
            report.tips.split("\n").forEach { Text("•  $it", color = Body, fontSize = 11.sp) }
        }
    }
}
