package com.example.fittrack

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val Purple = Color(0xFFB388FF)
private val Pink = Color(0xFFF48FB1)
private val Indigo = Color(0xFF9FA8DA)
private val MutedText = Color(0xFF9AA0AA)
private val BodyText = Color(0xFFD0D3D9)

private fun formatTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

private fun formatClock(hour: Int, minute: Int): String =
    LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a"))

private fun formatDuration(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun typeLabel(type: String) = if (type == SleepType.NAP) "Power Nap" else "Night sleep"

private fun sourceLabel(source: String) = when (source) {
    SleepSource.MANUAL -> "Manual"
    SleepSource.ESTIMATED -> "Estimated"
    SleepSource.EDITED -> "Estimate (edited)"
    else -> "Stopwatch"
}

// Turns two clock times into real timestamps. The start is always the latest
// time-of-day BEFORE the end, so 11:00 PM to 7:00 AM crosses midnight correctly.
private fun resolveRange(sh: Int, sm: Int, eh: Int, em: Int, endDate: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val end = endDate.atTime(eh, em).atZone(zone)
    var start = endDate.atTime(sh, sm).atZone(zone)
    if (!start.isBefore(end)) start = start.minusDays(1)
    return Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
}

// Emits the Sleep, Power Nap and Total Sleep cards plus their dialogs.
@Composable
fun SleepSection(store: SleepStore) {
    var newType by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SleepSession?>(null) }

    val nightSessions = store.sessionsToday.filter { it.type == SleepType.NIGHT }
    val napSessions = store.sessionsToday.filter { it.type == SleepType.NAP }

    SleepCard(
        minutes = store.nightMinutesToday,
        sleepingSinceMs = store.sleepingSinceMs,
        sessions = nightSessions,
        onStart = { store.startSleep() },
        onWake = { store.wake() },
        onCancel = { store.cancelSleep() },
        onEnterTimes = { newType = SleepType.NIGHT },
        onEdit = { editing = it },
        onDelete = { store.delete(it) }
    )
    NapCard(
        minutes = store.napMinutesToday,
        sessions = napSessions,
        onAdd = { newType = SleepType.NAP },
        onEdit = { editing = it },
        onDelete = { store.delete(it) }
    )
    TotalSleepCard(
        totalMinutes = store.minutesToday,
        nightMinutes = store.nightMinutesToday,
        napMinutes = store.napMinutesToday
    )

    newType?.let { type ->
        SleepEditDialog(
            type = type,
            initial = null,
            onDismiss = { newType = null },
            onSave = { start, end ->
                store.addManual(type, start, end)
                newType = null
            }
        )
    }
    editing?.let { session ->
        SleepEditDialog(
            type = session.type,
            initial = session,
            onDismiss = { editing = null },
            onSave = { start, end ->
                store.update(session.id, start, end)
                editing = null
            }
        )
    }
}

// Asked after you wake up. If the Sleep Quality Monitor ran, it shows the estimate (movement,
// screen on/off, light and sound). If not, it still asks once every morning for your sleep time.
@Composable
fun WakeUpPrompt(sleep: SleepStore) {
    val context = LocalContext.current
    val monitor = remember { MonitorStore.get(context) }
    val prefs = remember { context.getSharedPreferences("morning_prompt", Context.MODE_PRIVATE) }
    val todayText = LocalDate.now().toString()
    var askedDay by remember { mutableStateOf(prefs.getString("day", "") ?: "") }
    var snoozed by remember { mutableStateOf(false) }   // back button: ask again next time
    var entering by remember { mutableStateOf(false) }
    val estimate = monitor.wakeEstimate

    fun markAsked() {
        prefs.edit().putString("day", todayText).apply()
        askedDay = todayText
    }

    if (estimate != null) {
        // Already recorded by hand or with the stopwatch, so there is nothing to ask
        val logged = remember(estimate.sessionId, sleep.minutesToday) {
            sleep.hasNightBetween(estimate.startMs, estimate.endMs)
        }
        if (logged) {
            LaunchedEffect(estimate.sessionId) {
                monitor.finishWakeQuestion()
                markAsked()
            }
            return
        }
        if (snoozed) return
        if (entering) {
            SleepEditDialog(
                type = SleepType.NIGHT,
                initial = SleepSession(
                    0L, estimate.startMs, estimate.endMs, SleepType.NIGHT, SleepSource.ESTIMATED
                ),
                onDismiss = { entering = false },
                onSave = { start, end ->
                    sleep.addSession(SleepType.NIGHT, start, end, SleepSource.EDITED)
                    monitor.finishWakeQuestion()
                    markAsked()
                    entering = false
                }
            )
            return
        }
        val minutes = ((estimate.endMs - estimate.startMs) / 60000).toInt()
        AlertDialog(
            onDismissRequest = { snoozed = true },
            title = { Text("Good morning") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Estimated from your phone's movement, screen on/off, light and sound:",
                        fontSize = 13.sp
                    )
                    Text(
                        "${formatTime(estimate.startMs)} - ${formatTime(estimate.endMs)}",
                        color = Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                    Text(formatDuration(minutes), fontSize = 14.sp)
                    Text("Save it, or enter your own times.", color = MutedText, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { entering = true }) { Text("Enter manually") }
                    TextButton(onClick = {
                        sleep.addSession(
                            SleepType.NIGHT, estimate.startMs, estimate.endMs, SleepSource.ESTIMATED
                        )
                        monitor.finishWakeQuestion()
                        markAsked()
                    }) { Text("Save") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    monitor.finishWakeQuestion()
                    markAsked()
                }) { Text("Skip") }
            }
        )
        return
    }

    // No estimate (monitor not used): ask once each morning if no night sleep is recorded today
    val needsAsk = askedDay != todayText && LocalTime.now().hour >= 4 &&
            sleep.nightMinutesToday == 0 && sleep.sleepingSinceMs == 0L
    if (!needsAsk || snoozed) return
    if (entering) {
        SleepEditDialog(
            type = SleepType.NIGHT,
            initial = null,
            onDismiss = { entering = false },
            onSave = { start, end ->
                sleep.addManual(SleepType.NIGHT, start, end)
                markAsked()
                entering = false
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = { snoozed = true },
        title = { Text("Good morning") },
        text = {
            Text(
                "How did you sleep last night? Enter your sleep time to keep your sleep record.",
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = { entering = true }) { Text("Enter sleep time") }
        },
        dismissButton = {
            TextButton(onClick = { markAsked() }) { Text("Not now") }
        }
    )
}

@Composable
private fun CardShell(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun SessionRow(
    s: SleepSession,
    onEdit: (SleepSession) -> Unit,
    onDelete: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(s) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${formatTime(s.startMs)} - ${formatTime(s.endMs)}  ·  " +
                        formatDuration(((s.endMs - s.startMs) / 60000).toInt()),
                color = BodyText, fontSize = 14.sp
            )
            Text(sourceLabel(s.source), color = MutedText, fontSize = 12.sp)
        }
        Text("Edit", color = MutedText, fontSize = 12.sp)
    }
}

@Composable
fun SleepCard(
    minutes: Int,
    sleepingSinceMs: Long,
    sessions: List<SleepSession>,
    onStart: () -> Unit,
    onWake: () -> Unit,
    onCancel: () -> Unit,
    onEnterTimes: () -> Unit,
    onEdit: (SleepSession) -> Unit,
    onDelete: (Long) -> Unit
) {
    val sleeping = sleepingSinceMs > 0L
    CardShell {
        Column {
            Text("Sleep (night)", color = MutedText, fontSize = 14.sp)
            Text(
                formatDuration(minutes),
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (sleeping) onWake else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple,
                    contentColor = Color.Black
                )
            ) { Text(if (sleeping) "I woke up" else "Start sleep") }
            OutlinedButton(onClick = onEnterTimes) { Text("Enter times") }
        }
        if (sleeping) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sleeping since ${formatTime(sleepingSinceMs)}",
                    color = Purple, fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MutedText, fontSize = 12.sp)
                }
            }
        }
        sessions.forEach { SessionRow(it, onEdit, onDelete) }
        if (sessions.isNotEmpty()) {
            Text("Tap a session to edit it", color = MutedText, fontSize = 11.sp)
        }
    }
}

@Composable
fun NapCard(
    minutes: Int,
    sessions: List<SleepSession>,
    onAdd: () -> Unit,
    onEdit: (SleepSession) -> Unit,
    onDelete: (Long) -> Unit
) {
    CardShell {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Power Nap", color = MutedText, fontSize = 14.sp)
                Text(
                    formatDuration(minutes),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Pink,
                    contentColor = Color.Black
                )
            ) { Text("+ Add nap") }
        }
        sessions.forEach { SessionRow(it, onEdit, onDelete) }
        if (sessions.isNotEmpty()) {
            Text("Tap a session to edit it", color = MutedText, fontSize = 11.sp)
        }
    }
}

@Composable
fun TotalSleepCard(totalMinutes: Int, nightMinutes: Int, napMinutes: Int) {
    CardShell {
        Column {
            Text("Total Sleep", color = MutedText, fontSize = 14.sp)
            Text(
                formatDuration(totalMinutes),
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }
        Text(
            "Night ${formatDuration(nightMinutes)}  ·  Power Nap ${formatDuration(napMinutes)}",
            color = MutedText, fontSize = 12.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SleepEditDialog(
    type: String,
    initial: SleepSession?,
    onDismiss: () -> Unit,
    onSave: (Long, Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val isNap = type == SleepType.NAP
    val initStart = initial?.let { Instant.ofEpochMilli(it.startMs).atZone(zone) }
    val initEnd = initial?.let { Instant.ofEpochMilli(it.endMs).atZone(zone) }

    var startH by remember { mutableIntStateOf(initStart?.hour ?: if (isNap) 14 else 23) }
    var startM by remember { mutableIntStateOf(initStart?.minute ?: 0) }
    var endH by remember { mutableIntStateOf(initEnd?.hour ?: if (isNap) 14 else 7) }
    var endM by remember { mutableIntStateOf(initEnd?.minute ?: if (isNap) 30 else 0) }
    var picking by remember { mutableStateOf<String?>(null) }

    // Editing keeps the original wake-up date. A new entry uses today, or yesterday
    // if the chosen end time hasn't happened yet today.
    val endDate: LocalDate = initEnd?.toLocalDate() ?: run {
        val today = LocalDate.now()
        if (!isNap && today.atTime(endH, endM).atZone(zone).isAfter(ZonedDateTime.now())) {
            today.minusDays(1)
        } else {
            today
        }
    }
    val (startMs, endMs) = resolveRange(startH, startM, endH, endM, endDate)
    val minutes = ((endMs - startMs) / 60000).toInt()
    val sameTime = startH == endH && startM == endM

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "Add ${typeLabel(type)}" else "Edit ${typeLabel(type)}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { picking = "start" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start:  ${formatClock(startH, startM)}") }
                OutlinedButton(
                    onClick = { picking = "end" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("End:  ${formatClock(endH, endM)}") }
                Text(
                    if (sameTime) "Start and end can't be the same"
                    else "Duration: ${formatDuration(minutes)}",
                    color = if (sameTime) Color(0xFFFF8A80) else Purple,
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(startMs, endMs) }, enabled = !sameTime) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    when (picking) {
        "start" -> DialTimePickerDialog(
            title = "Start time",
            initialHour = startH,
            initialMinute = startM,
            onConfirm = { h, m ->
                startH = h
                startM = m
                picking = null
            },
            onDismiss = { picking = null }
        )
        "end" -> DialTimePickerDialog(
            title = "End time",
            initialHour = endH,
            initialMinute = endM,
            onConfirm = { h, m ->
                endH = h
                endM = m
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}