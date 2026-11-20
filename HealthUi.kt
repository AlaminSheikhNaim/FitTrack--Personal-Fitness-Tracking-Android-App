package com.example.fittrack

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val HCard = Color(0xFF1A1D23)
private val HMuted = Color(0xFF9AA0AA)
private val HBody = Color(0xFFD0D3D9)

// ---------- Feet and inches ----------

private const val CM_PER_INCH = 2.54f

fun cmToInches(cm: Float): Float = cm / CM_PER_INCH

fun inchesToCm(inches: Float): Float = inches * CM_PER_INCH

// 67 inches -> "5 ft 7 in". Fractions are rounded to the nearest half inch ("5 ft 7.5 in").
fun formatHeightInches(totalInches: Float): String {
    val halves = Math.round(totalInches * 2f)
    val feet = halves / 24
    val inches = (halves % 24) / 2f
    return "$feet ft ${cleanNum(inches)} in"
}

// Height as stored in cm -> "5 ft 7 in"
fun formatHeight(cm: Float): String = formatHeightInches(cmToInches(cm))

// Short form for chart and ruler labels: 5'7"
private fun heightAxis(totalInches: Float): String {
    val halves = Math.round(totalInches * 2f)
    val feet = halves / 24
    val inches = (halves % 24) / 2f
    return "$feet'${cleanNum(inches)}\""
}

// ---------- Weight and height settings ----------

private enum class ReportRange(val label: String, val days: Int) {
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365)
}

// Everything that differs between the weight and height screens.
// "input" values are what you see and pick (kg, or inches). "stored" values are
// what the database holds (kg, or cm).
private class MeasureKind(
    val type: String,
    val title: String,
    val color: Color,
    val min: Float,
    val max: Float,
    val minorStep: Float,
    val majorEvery: Int,
    val dpPerMajor: Float,
    val defaultInput: Float,
    val fromStored: (Float) -> Float,
    val toStored: (Float) -> Float,
    val show: (Float) -> String,
    val showDelta: (Float) -> String,
    val axis: (Float) -> String,
    val rulerLabel: (Float) -> String,
    val quick: List<Pair<String, Float>>
)

private val WeightKind = MeasureKind(
    type = MeasureType.WEIGHT,
    title = "Weight",
    color = Color(0xFFFF8A65),
    min = 20f,
    max = 300f,
    minorStep = 0.1f,
    majorEvery = 10,
    dpPerMajor = 60f,
    defaultInput = 60f,
    fromStored = { it },
    toStored = { it },
    show = { "${cleanNum(it)} kg" },
    showDelta = { "${cleanNum(it)} kg" },
    axis = { cleanNum(it) },
    rulerLabel = { Math.round(it).toString() },
    quick = listOf("-1" to -1f, "-0.1" to -0.1f, "+0.1" to 0.1f, "+1" to 1f)
)

private val HeightKind = MeasureKind(
    type = MeasureType.HEIGHT,
    title = "Height",
    color = Color(0xFF4DD0E1),
    min = 20f,
    max = 100f,
    minorStep = 0.5f,
    majorEvery = 2,
    dpPerMajor = 48f,
    defaultInput = 65f,
    fromStored = { cmToInches(it) },
    toStored = { inchesToCm(it) },
    show = { formatHeightInches(it) },
    showDelta = { "${cleanNum(it)} in" },
    axis = { heightAxis(it) },
    rulerLabel = { heightAxis(it) },
    quick = listOf("-1 ft" to -12f, "-1 in" to -1f, "+1 in" to 1f, "+1 ft" to 12f)
)

private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

private fun dateTimeText(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))

private fun dayText(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("d MMM yyyy"))

// ---------- Public entry points used by the dashboard ----------

@Composable
fun WeightDetail(health: HealthStore, onBack: () -> Unit) =
    MeasurementDetail(WeightKind, health, onBack)

@Composable
fun HeightDetail(health: HealthStore, onBack: () -> Unit) =
    MeasurementDetail(HeightKind, health, onBack)

@Composable
fun WeightDialog(health: HealthStore, onDismiss: () -> Unit) =
    MeasurementDialog(
        WeightKind, health,
        health.weights.lastOrNull()?.value ?: WeightKind.defaultInput, onDismiss
    )

@Composable
fun HeightDialog(health: HealthStore, onDismiss: () -> Unit) =
    MeasurementDialog(
        HeightKind, health,
        health.heights.lastOrNull()?.let { cmToInches(it.value) } ?: HeightKind.defaultInput,
        onDismiss
    )

// ---------- Small building blocks ----------

@Composable
private fun HealthCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HCard, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun RangeChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else Color(0xFF2A2E36))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = HMuted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Date picker ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms != null) {
                    val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    val today = LocalDate.now()
                    onPick(if (picked.isAfter(today)) today else picked)
                } else {
                    onDismiss()
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}

// ---------- Ruler (drag left or right, like Samsung Health) ----------

@Composable
private fun RulerPicker(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    minorStep: Float,
    majorEvery: Int,
    dpPerMajor: Float,
    accent: Color,
    labelOf: (Float) -> String
) {
    val density = LocalDensity.current
    val pxPerMajor = with(density) { dpPerMajor.dp.toPx() }
    val pxPerUnit = pxPerMajor / (minorStep * majorEvery)
    val labelPx = with(density) { 12.sp.toPx() }
    val paint = remember(labelPx) {
        Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.parseColor("#9AA0AA")
            textSize = labelPx
            textAlign = Paint.Align.CENTER
        }
    }
    val onChange by rememberUpdatedState(onValueChange)

    // 'exact' moves smoothly while dragging; the reported value snaps to the nearest tick
    var exact by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) {
        val snapped = (exact / minorStep).roundToInt() * minorStep
        if (abs(value - snapped) > minorStep / 2f) exact = value
    }

    val dragState = rememberDraggableState { delta ->
        exact = (exact - delta / pxPerUnit).coerceIn(min, max)
        onChange(round1((exact / minorStep).roundToInt() * minorStep))
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .draggable(state = dragState, orientation = Orientation.Horizontal)
    ) {
        val centerX = size.width / 2f
        val half = centerX / pxPerUnit
        val firstIdx = floor((exact - half) / minorStep).toInt()
        val lastIdx = ceil((exact + half) / minorStep).toInt()
        for (i in firstIdx..lastIdx) {
            val v = i * minorStep
            if (v < min - 0.001f || v > max + 0.001f) continue
            val x = centerX + (v - exact) * pxPerUnit
            val major = i % majorEvery == 0
            val mid = majorEvery % 2 == 0 && i % (majorEvery / 2) == 0
            val tick = if (major) 36.dp.toPx() else if (mid) 24.dp.toPx() else 15.dp.toPx()
            drawLine(
                color = if (major) Color(0xFF8A92A3) else Color(0xFF4A5060),
                start = Offset(x, size.height - tick),
                end = Offset(x, size.height),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx()
            )
            if (major) {
                drawIntoCanvas { cv ->
                    cv.nativeCanvas.drawText(
                        labelOf(v), x, size.height - tick - 6.dp.toPx(), paint
                    )
                }
            }
        }
        drawLine(
            color = accent,
            start = Offset(centerX, 0f),
            end = Offset(centerX, size.height),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

// ---------- Add weight or height ----------

@Composable
private fun MeasurementDialog(
    kind: MeasureKind,
    health: HealthStore,
    initialValue: Float,
    onDismiss: () -> Unit
) {
    val start = ((initialValue / kind.minorStep).roundToInt() * kind.minorStep)
        .coerceIn(kind.min, kind.max)
    var value by remember { mutableFloatStateOf(start) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }

    fun nudge(delta: Float) {
        value = round1(value + delta).coerceIn(kind.min, kind.max)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${kind.title.lowercase()}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    kind.show(value),
                    color = kind.color, fontSize = 34.sp, fontWeight = FontWeight.Bold
                )
                RulerPicker(
                    value = value,
                    onValueChange = { value = it.coerceIn(kind.min, kind.max) },
                    min = kind.min,
                    max = kind.max,
                    minorStep = kind.minorStep,
                    majorEvery = kind.majorEvery,
                    dpPerMajor = kind.dpPerMajor,
                    accent = kind.color,
                    labelOf = kind.rulerLabel
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kind.quick.forEach { (label, delta) ->
                        OutlinedButton(
                            onClick = { nudge(delta) },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                OutlinedButton(
                    onClick = { pickDate = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Date: ${dayText(date)}") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ts = date.atTime(LocalTime.now()).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                health.addMeasurement(kind.type, ts, kind.toStored(value))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickDate) {
        DatePickDialog(
            initial = date,
            onPick = {
                date = it
                pickDate = false
            },
            onDismiss = { pickDate = false }
        )
    }
}

// ---------- Chart ----------

@Composable
private fun LineChart(
    points: List<Pair<Long, Float>>,
    startMs: Long,
    endMs: Long,
    color: Color,
    startLabel: String,
    endLabel: String,
    axisLabel: (Float) -> String
) {
    val textPx = with(LocalDensity.current) { 11.sp.toPx() }
    val paint = remember(textPx) {
        Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.parseColor("#9AA0AA")
            textSize = textPx
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
    ) {
        val leftPad = 44.dp.toPx()
        val rightPad = 12.dp.toPx()
        val topPad = 12.dp.toPx()
        val bottomPad = 26.dp.toPx()
        val w = size.width - leftPad - rightPad
        val h = size.height - topPad - bottomPad

        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        if (hi - lo < 0.5f) {
            lo -= 1f
            hi += 1f
        } else {
            val pad = (hi - lo) * 0.15f
            lo -= pad
            hi += pad
        }
        val bottomValue = lo
        val topValue = hi
        val span = (endMs - startMs).coerceAtLeast(1L).toFloat()

        fun xOf(t: Long): Float = leftPad + w * ((t - startMs).toFloat() / span).coerceIn(0f, 1f)
        fun yOf(v: Float): Float = topPad + h * (1f - (v - bottomValue) / (topValue - bottomValue))

        // Horizontal guide lines with their values
        for (k in 0..2) {
            val v = topValue - (topValue - bottomValue) * k / 2f
            val y = yOf(v)
            drawLine(
                color = Color(0xFF2A2E36),
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1.dp.toPx()
            )
            drawIntoCanvas { cv ->
                paint.textAlign = Paint.Align.LEFT
                cv.nativeCanvas.drawText(axisLabel(v), 2.dp.toPx(), y + 4.dp.toPx(), paint)
            }
        }

        // Date labels under the chart
        drawIntoCanvas { cv ->
            paint.textAlign = Paint.Align.LEFT
            cv.nativeCanvas.drawText(startLabel, leftPad, size.height - 6.dp.toPx(), paint)
            paint.textAlign = Paint.Align.RIGHT
            cv.nativeCanvas.drawText(endLabel, size.width - rightPad, size.height - 6.dp.toPx(), paint)
        }

        // The line and its dots
        val sorted = points.sortedBy { it.first }
        val path = Path()
        sorted.forEachIndexed { i, p ->
            val x = xOf(p.first)
            val y = yOf(p.second)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        sorted.forEach { p ->
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(xOf(p.first), yOf(p.second)))
        }
    }
}

// ---------- Weekly, monthly and yearly report ----------

private class Bucket(val label: String, val avg: Float?, val count: Int)

// Averages of the stored values, grouped by day (week), 6-day block (month) or month (year)
private fun buildReport(entries: List<Measurement>, range: ReportRange): List<Bucket> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    fun dayOf(m: Measurement): LocalDate = Instant.ofEpochMilli(m.tsMs).atZone(zone).toLocalDate()
    fun bucket(label: String, list: List<Measurement>): Bucket {
        val avg = if (list.isEmpty()) null else list.map { it.value }.average().toFloat()
        return Bucket(label, avg, list.size)
    }

    return when (range) {
        ReportRange.WEEK -> (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            bucket(
                d.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                entries.filter { dayOf(it) == d }
            )
        }
        ReportRange.MONTH -> (4 downTo 0).map { block ->
            val end = today.minusDays(6L * block)
            val start = end.minusDays(5)
            bucket(
                "${start.format(DateTimeFormatter.ofPattern("d MMM"))} - " +
                        end.format(DateTimeFormatter.ofPattern("d MMM")),
                entries.filter {
                    val d = dayOf(it)
                    !d.isBefore(start) && !d.isAfter(end)
                }
            )
        }
        ReportRange.YEAR -> (11 downTo 0).map { back ->
            val ym = YearMonth.from(today).minusMonths(back.toLong())
            bucket(
                ym.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                entries.filter { YearMonth.from(dayOf(it)) == ym }
            )
        }
    }
}

// ---------- Weight and height screen ----------

@Composable
private fun MeasurementDetail(kind: MeasureKind, health: HealthStore, onBack: () -> Unit) {
    val entries = if (kind.type == MeasureType.WEIGHT) health.weights else health.heights
    var range by remember { mutableStateOf(ReportRange.WEEK) }
    var showAdd by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val latest = entries.lastOrNull()
    val previous = if (entries.size >= 2) entries[entries.size - 2] else null

    DetailScreen(kind.title, onBack) {
        HealthCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Latest", color = HMuted, fontSize = 13.sp)
                    Text(
                        if (latest != null) kind.show(kind.fromStored(latest.value)) else "No data yet",
                        color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold
                    )
                    if (latest != null) {
                        Text(dateTimeText(latest.tsMs), color = HMuted, fontSize = 12.sp)
                    }
                    if (latest != null && previous != null) {
                        val diff = kind.fromStored(latest.value) - kind.fromStored(previous.value)
                        val arrow = if (diff > 0.01f) "▲" else if (diff < -0.01f) "▼" else "•"
                        Text(
                            "$arrow ${kind.showDelta(abs(diff))} since the previous entry",
                            color = kind.color, fontSize = 12.sp
                        )
                    }
                }
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = kind.color,
                        contentColor = Color.Black
                    )
                ) { Text("+ Add") }
            }
        }

        if (kind.type == MeasureType.WEIGHT) {
            val w = health.weights.lastOrNull()?.value
            val h = health.heights.lastOrNull()?.value
            var showBmi by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().clickable { showBmi = true }) {
                HealthCard {
                    Text("BMI", color = HMuted, fontSize = 14.sp)
                    if (w != null && h != null) {
                        val bmi = bmiOf(w, h)
                        Text(
                            "${cleanNum(bmi)}  ·  ${bmiLabel(bmi)}",
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                        )
                        Text("From your latest weight and height", color = HMuted, fontSize = 12.sp)
                    } else {
                        Text("Add your height to see your BMI.", color = HBody, fontSize = 13.sp)
                    }
                    Text("Tap to see your BMI records and chart", color = HMuted, fontSize = 11.sp)
                }
            }
            if (showBmi) BmiDialog(health) { showBmi = false }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportRange.values().forEach { r ->
                RangeChip(r.label, r == range, kind.color) { range = r }
            }
        }

        val today = LocalDate.now()
        val startDay = today.minusDays((range.days - 1).toLong())
        val startMs = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val inRange = entries.filter { it.tsMs in startMs..endMs }
        val labelPattern = if (range == ReportRange.YEAR) "MMM yyyy" else "d MMM"

        HealthCard {
            Text("${kind.title} · ${range.label}", color = HMuted, fontSize = 14.sp)
            if (inRange.isEmpty()) {
                Text("No entries in this period. Tap + Add.", color = HBody, fontSize = 13.sp)
            } else {
                val values = inRange.map { kind.fromStored(it.value) }
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatBox("Average", kind.show(values.average().toFloat()), Modifier.weight(1f))
                    StatBox("Lowest", kind.show(values.min()), Modifier.weight(1f))
                    StatBox("Highest", kind.show(values.max()), Modifier.weight(1f))
                }
                LineChart(
                    points = inRange.map { Pair(it.tsMs, kind.fromStored(it.value)) },
                    startMs = startMs,
                    endMs = endMs,
                    color = kind.color,
                    startLabel = startDay.format(DateTimeFormatter.ofPattern(labelPattern)),
                    endLabel = today.format(DateTimeFormatter.ofPattern(labelPattern)),
                    axisLabel = kind.axis
                )
            }
        }

        HealthCard {
            Text("${range.label} report (average)", color = HMuted, fontSize = 14.sp)
            buildReport(entries, range).forEach { b ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(b.label, color = HBody, fontSize = 14.sp)
                    Text(
                        if (b.avg == null) "—"
                        else "${kind.show(kind.fromStored(b.avg))}  (${b.count})",
                        color = if (b.avg == null) HMuted else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HealthCard {
            Text("History", color = HMuted, fontSize = 14.sp)
            if (entries.isEmpty()) {
                Text("Nothing recorded yet.", color = HBody, fontSize = 13.sp)
            }
            entries.asReversed().take(60).forEach { e ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            kind.show(kind.fromStored(e.value)),
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Text(dateTimeText(e.tsMs), color = HMuted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { health.deleteMeasurement(e.id) }) {
                        Text("Remove", color = HMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showAdd) {
        MeasurementDialog(
            kind, health,
            if (latest != null) kind.fromStored(latest.value) else kind.defaultInput,
            onDismiss = { showAdd = false }
        )
    }
}

// ---------- Vaccination ----------

@Composable
fun VaccineDialog(health: HealthStore, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add vaccine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Vaccine name (e.g. Hepatitis B)") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { pickDate = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Date taken: ${dayText(date)}") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    health.addVaccine(name.trim(), date)
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickDate) {
        DatePickDialog(
            initial = date,
            onPick = {
                date = it
                pickDate = false
            },
            onDismiss = { pickDate = false }
        )
    }
}

@Composable
fun VaccinationDetail(health: HealthStore, onBack: () -> Unit) {
    val purple = Color(0xFFBA68C8)
    var showAdd by remember { mutableStateOf(false) }

    DetailScreen("Vaccination", onBack) {
        HealthCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Vaccines recorded", color = HMuted, fontSize = 13.sp)
                    Text(
                        "${health.vaccines.size}",
                        color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = purple,
                        contentColor = Color.Black
                    )
                ) { Text("+ Add") }
            }
        }

        HealthCard {
            Text("History", color = HMuted, fontSize = 14.sp)
            if (health.vaccines.isEmpty()) {
                Text("No vaccines recorded yet. Tap + Add.", color = HBody, fontSize = 13.sp)
            }
            health.vaccines.forEach { v ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            v.name, color = Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Taken ${dayText(LocalDate.parse(v.day))}",
                            color = HMuted, fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { health.deleteVaccine(v.id) }) {
                        Text("Remove", color = HMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showAdd) {
        VaccineDialog(health, onDismiss = { showAdd = false })
    }
}

// ---------- BMI records and standard chart ----------

private class BmiBand(val name: String, val range: String, val from: Float, val to: Float)

private val bmiBands = listOf(
    BmiBand("Underweight", "below 18.5", 15f, 18.5f),
    BmiBand("Normal", "18.5 - 24.9", 18.5f, 25f),
    BmiBand("Overweight", "25 - 29.9", 25f, 30f),
    BmiBand("Obese class I", "30 - 34.9", 30f, 35f),
    BmiBand("Obese class II", "35 - 39.9", 35f, 40f),
    BmiBand("Obese class III", "40 and above", 40f, 45f)
)

private fun bmiColor(bmi: Float): Color = when {
    bmi < 18.5f -> Color(0xFF4FC3F7)
    bmi < 25f -> Color(0xFF00E5A0)
    bmi < 30f -> Color(0xFFFFB74D)
    bmi < 35f -> Color(0xFFFF8A65)
    bmi < 40f -> Color(0xFFFF5252)
    else -> Color(0xFFB71C1C)
}

private fun bmiBandName(bmi: Float): String =
    bmiBands.firstOrNull { bmi < it.to }?.name ?: "Obese class III"

@Composable
private fun BmiDialog(health: HealthStore, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    // BMI of every saved weight, using the height known at that time (newest first)
    val rows = health.weights.mapNotNull { w ->
        val h = health.heights.lastOrNull { it.tsMs <= w.tsMs } ?: health.heights.firstOrNull()
        if (h == null) null else Triple(w, h.value, bmiOf(w.value, h.value))
    }.reversed()
    val latest = rows.firstOrNull()?.third

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BMI") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (latest == null) {
                    Text("Add your weight and height to see your BMI.", fontSize = 13.sp)
                } else {
                    Text(
                        "${cleanNum(latest)}  ·  ${bmiBandName(latest)}",
                        color = bmiColor(latest), fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                }

                Text("BMI standard chart", color = HMuted, fontSize = 12.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                ) {
                    bmiBands.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(b.to - b.from)
                                .fillMaxHeight()
                                .background(bmiColor(b.from + 0.01f))
                        )
                    }
                }
                if (latest != null) {
                    val frac = ((latest - 15f) / 30f).coerceIn(0.03f, 0.97f)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.weight(frac))
                        Text("▲", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f - frac))
                    }
                }
                bmiBands.forEach { b ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(bmiColor(b.from + 0.01f))
                        )
                        Text(b.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(b.range, color = HMuted, fontSize = 12.sp)
                    }
                }

                Text("Previous records", color = HMuted, fontSize = 12.sp)
                if (rows.isEmpty()) {
                    Text("No records yet.", color = HMuted, fontSize = 12.sp)
                }
                rows.forEach { (w, _, bmi) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            Instant.ofEpochMilli(w.tsMs).atZone(zone).toLocalDate().format(dayFormat),
                            fontSize = 12.sp, modifier = Modifier.weight(1.3f)
                        )
                        Text("${cleanNum(w.value)} kg", color = HMuted, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Text(
                            "${cleanNum(bmi)} ${bmiBandName(bmi)}",
                            color = bmiColor(bmi), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1.5f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}