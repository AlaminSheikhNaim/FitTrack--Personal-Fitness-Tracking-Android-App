package com.example.fittrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val RCard = Color(0xFF1A1D23)
private val RMuted = Color(0xFF9AA0AA)
private val RBody = Color(0xFFD0D3D9)

enum class Period(val label: String) {
    DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly")
}

// Green when the goal is reached, then lighter green, amber and red the further below it is
fun ratioColor(r: Float): Color = when {
    r >= 1f -> Color(0xFF00E5A0)
    r >= 0.75f -> Color(0xFF81C784)
    r >= 0.5f -> Color(0xFFFFB74D)
    else -> Color(0xFFEF9A9A)
}

private class ChartBar(
    val label: String, val value: Float, val ratio: Float?,
    val start: LocalDate, val end: LocalDate
)

@Composable
private fun BarChart(
    bars: List<ChartBar>, goal: Float?, accent: Color, labelEvery: Int,
    selected: Int?, onSelect: (Int) -> Unit
) {
    val top = maxOf(bars.maxOfOrNull { it.value } ?: 0f, goal ?: 0f, 1f) * 1.1f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(bars.size) {
                detectTapGestures { off ->
                    val i = (off.x / (size.width.toFloat() / bars.size)).toInt()
                    onSelect(i.coerceIn(0, bars.size - 1))
                }
            }
    ) {
        val slot = size.width / bars.size
        val barWidth = slot * 0.62f
        bars.forEachIndexed { i, b ->
            if (selected == i) {
                drawRoundRect(
                    color = Color(0x33FFFFFF),
                    topLeft = Offset(i * slot, 0f),
                    size = Size(slot, size.height),
                    cornerRadius = CornerRadius(10f, 10f)
                )
            }
            val h = size.height * (b.value / top)
            if (h > 0f) {
                drawRoundRect(
                    color = b.ratio?.let { ratioColor(it) } ?: accent,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
        if (goal != null) {
            val y = size.height - size.height * (goal / top)
            drawLine(
                color = Color(0xFF9AA0AA),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        bars.forEachIndexed { i, b ->
            Text(
                if (i % labelEvery == 0) b.label else "",
                color = RMuted, fontSize = 9.sp, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Shown when a bar is tapped: that day (or week / month) with its numbers
@Composable
private fun BarDetailPanel(
    b: ChartBar, data: Map<LocalDate, Int>, goal: Int, format: (Int) -> String,
    accent: Color, rating: ((Float) -> Pair<String, Color>)?,
    dayDetail: ((LocalDate) -> List<Pair<String, String>>)?,
    dayExtra: (@Composable (LocalDate) -> Unit)? = null
) {
    val single = b.start == b.end
    val days = generateSequence(b.start) { it.plusDays(1) }.takeWhile { !it.isAfter(b.end) }.toList()
    val vals = days.map { data[it] ?: 0 }
    val total = vals.sum()
    val logged = vals.count { it > 0 }
    val title = if (single) b.start.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"))
    else b.start.format(DateTimeFormatter.ofPattern("d MMM")) + " – " + b.end.format(DateTimeFormatter.ofPattern("d MMM yyyy"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF23272F), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (logged == 0) {
            Text("No data for this ${if (single) "day" else "period"}.", color = RMuted, fontSize = 12.sp)
            if (single) dayExtra?.invoke(b.start)
            return@Column
        }
        val avg = total / logged
        Row(modifier = Modifier.fillMaxWidth()) {
            StatText("Total", format(total), Modifier.weight(1f))
            StatText(if (single) "Goal reached" else "Average / day", if (single) "${total * 100 / goal}%" else format(avg), Modifier.weight(1f))
        }
        rating?.let {
            val (label, color) = it((if (single) total else avg).toFloat() / goal)
            Text("Rating: $label", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (single) {
            dayDetail?.invoke(b.start)?.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = RBody, fontSize = 12.sp)
                    Text(v, color = Color.White, fontSize = 12.sp)
                }
            }
            if (single) dayExtra?.invoke(b.start)
        } else if (days.size <= 31) {
            days.forEachIndexed { i, d ->
                if (vals[i] > 0) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE d MMM")), color = RBody, fontSize = 12.sp)
                    Text(format(vals[i]), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatText(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = RMuted, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else Color(0xFF2A2E36))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label, fontSize = 12.sp,
            color = if (selected) Color.Black else RBody
        )
    }
}

// One report card with a bar chart for the chosen period. Used by Water and Workout.
// totalsBetween gives the amount per day (only days with entries are included).
@Composable
fun PeriodReportCard(
    title: String,
    periods: List<Period>,
    refreshKey: Any,
    today: LocalDate,
    totalsBetween: (LocalDate, LocalDate) -> Map<LocalDate, Int>,
    goalPerDay: Int,
    format: (Int) -> String,
    accent: Color,
    yearlyAverage: Boolean,
    rating: ((Float) -> Pair<String, Color>)? = null,
    dayDetail: ((LocalDate) -> List<Pair<String, String>>)? = null,
    dayExtra: (@Composable (LocalDate) -> Unit)? = null
) {
    var period by remember { mutableStateOf(periods.first()) }
    var selected by remember(period, refreshKey) { mutableStateOf<Int?>(null) }
    val locale = Locale.getDefault()
    val goal = goalPerDay.coerceAtLeast(1)

    val rangeStart: LocalDate = when (period) {
        Period.DAILY -> today.minusDays(6)
        Period.WEEKLY -> today.with(DayOfWeek.MONDAY).minusWeeks(7)
        Period.MONTHLY -> today.withDayOfMonth(1)
        Period.YEARLY -> today.withDayOfYear(1)
    }
    val data = remember(period, refreshKey, today) { totalsBetween(rangeStart, today) }

    val bars = ArrayList<ChartBar>()
    var goalLine: Float? = goal.toFloat()
    var labelEvery = 1
    when (period) {
        Period.DAILY -> for (i in 0..6) {
            val d = rangeStart.plusDays(i.toLong())
            val v = data[d] ?: 0
            bars.add(ChartBar(d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), v.toFloat(), v.toFloat() / goal, d, d))
        }
        Period.WEEKLY -> {
            goalLine = goal * 7f
            for (w in 0..7) {
                val ws = rangeStart.plusWeeks(w.toLong())
                var sum = 0
                for (k in 0..6) sum += data[ws.plusDays(k.toLong())] ?: 0
                bars.add(ChartBar(ws.format(DateTimeFormatter.ofPattern("d/M")), sum.toFloat(), sum / (goal * 7f), ws, ws.plusDays(6)))
            }
        }
        Period.MONTHLY -> {
            labelEvery = 5
            for (i in 0 until YearMonth.from(today).lengthOfMonth()) {
                val v = data[rangeStart.plusDays(i.toLong())] ?: 0
                bars.add(ChartBar((i + 1).toString(), v.toFloat(), v.toFloat() / goal, rangeStart.plusDays(i.toLong()), rangeStart.plusDays(i.toLong())))
            }
        }
        Period.YEARLY -> {
            if (!yearlyAverage) goalLine = null
            for (m in 1..12) {
                val ym = YearMonth.of(today.year, m)
                val days = (1..ym.lengthOfMonth()).mapNotNull { data[ym.atDay(it)] }
                val total = days.sum()
                val value = if (yearlyAverage) (if (days.isEmpty()) 0 else total / days.size) else total
                bars.add(
                    ChartBar(
                        ym.month.getDisplayName(TextStyle.SHORT, locale), value.toFloat(),
                        if (yearlyAverage) value.toFloat() / goal else null,
                        ym.atDay(1), ym.atEndOfMonth()
                    )
                )
            }
        }
    }

    val logged = data.values.count { it > 0 }
    val total = data.values.sum()
    val average = if (logged > 0) total / logged else 0
    val goalDays = data.values.count { it >= goal }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RCard, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = RMuted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEach { p -> PeriodChip(p.label, p == period, accent) { period = p } }
        }
        BarChart(bars, goalLine, accent, labelEvery, selected) { selected = it }
        selected?.let { idx ->
            bars.getOrNull(idx)?.let { b ->
                BarDetailPanel(b, data, goal, format, accent, rating, dayDetail, dayExtra)
            }
        } ?: Text("Tap any bar to see its details.", color = RMuted, fontSize = 11.sp)
        Row(modifier = Modifier.fillMaxWidth()) {
            StatText("Total", format(total), Modifier.weight(1f))
            StatText("Average / day", format(average), Modifier.weight(1f))
            StatText("Goal reached", "$goalDays days", Modifier.weight(1f))
        }
        if (rating != null && logged > 0) {
            val (label, color) = rating(average.toFloat() / goal)
            Text("Average rating: $label", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Based on $logged logged ${if (logged == 1) "day" else "days"}. " +
                    "The dashed line is your goal" +
                    if (period == Period.WEEKLY) " for a week." else ".",
            color = RMuted, fontSize = 11.sp
        )
    }
}

// ---------- Hydration rating ----------

class Hydration(
    val score: Int,
    val label: String,
    val color: Color,
    val goalPct: Int,
    val note: String
)

fun hydrationLevel(score: Int): Pair<String, Color> = when {
    score >= 85 -> Pair("Excellent", Color(0xFF00E5A0))
    score >= 70 -> Pair("Good", Color(0xFF81C784))
    score >= 50 -> Pair("Fair", Color(0xFFFFB74D))
    score >= 30 -> Pair("Low", Color(0xFFFF8A65))
    else -> Pair("Very low", Color(0xFFEF9A9A))
}

// Rating for a period from the average intake compared with the daily goal
fun hydrationFromRatio(ratio: Float): Pair<String, Color> =
    hydrationLevel((ratio.coerceIn(0f, 1f) * 100f).roundToInt())

// 70 points for how much you drank (against your goal), 30 points for how well it was spread.
// Drinking most of it within one hour scores low on the spread part.
fun hydrationRating(entries: List<WaterEntry>, goalMl: Int): Hydration {
    val total = entries.sumOf { it.ml }
    if (total <= 0) {
        return Hydration(0, "No water yet", Color(0xFF9AA0AA), 0, "Log your first glass to get a rating.")
    }
    val goal = goalMl.coerceAtLeast(1)
    var peak = 0
    for (a in entries) {
        var s = 0
        for (b in entries) {
            if (b.tsMs >= a.tsMs && b.tsMs < a.tsMs + 3_600_000L) s += b.ml
        }
        if (s > peak) peak = s
    }
    val peakShare = peak.toFloat() / total
    val amountPts = minOf(total.toFloat() / goal, 1f) * 70f
    val spreadPts = 30f * ((0.9f - peakShare) / 0.55f).coerceIn(0f, 1f)
    val score = (amountPts + spreadPts).roundToInt()
    val peakPct = (peakShare * 100f).roundToInt()
    var note = when {
        peakShare >= 0.6f -> "$peakPct% of your water was drunk within one hour. Sip through the day instead of all at once."
        peakShare <= 0.35f -> "Your water was well spread through the day."
        else -> "Fairly spread, with one bigger drinking time ($peakPct% within one hour)."
    }
    if (total > goal * 2) note += " That is more than double your goal."
    val (label, color) = hydrationLevel(score)
    return Hydration(score, label, color, total * 100 / goal, note)
}

@Composable
fun WaterReportSection(water: WaterStore, settings: AppSettings) {
    val goal = settings.waterGoalMl
    val today = ResetTimes.dayNow("water")
    val h = hydrationRating(water.entriesToday.toList(), goal)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RCard, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Hydration rating · today", color = RMuted, fontSize = 14.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(h.label, color = h.color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${h.score} / 100", color = RBody, fontSize = 14.sp)
        }
        Text("${h.goalPct}% of your ${formatWater(goal)} goal", color = RBody, fontSize = 13.sp)
        Text(h.note, color = RMuted, fontSize = 12.sp)
    }

    PeriodReportCard(
        title = "Water report",
        periods = listOf(Period.DAILY, Period.MONTHLY, Period.YEARLY),
        refreshKey = water.mlToday,
        today = today,
        totalsBetween = { s, e -> water.totalsBetween(s, e) },
        goalPerDay = goal,
        format = { formatWater(it) },
        accent = Color(0xFF4FC3F7),
        yearlyAverage = true,
        rating = ::hydrationFromRatio,
        dayDetail = { d ->
            water.entriesForDay(d).map { e ->
                java.time.Instant.ofEpochMilli(e.tsMs).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("h:mm a")) to formatWater(e.ml)
            }
        }
    )

    // The last days with their own rating (amount and spread)
    val recent = remember(water.mlToday, goal) {
        (1..6).map { back ->
            val d = today.minusDays(back.toLong())
            Pair(d, water.entriesForDay(d))
        }.filter { it.second.isNotEmpty() }
    }
    if (recent.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RCard, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent days", color = RMuted, fontSize = 14.sp)
            recent.forEach { (d, entries) ->
                val r = hydrationRating(entries, goal)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        d.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        color = RBody, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatWater(entries.sumOf { it.ml }),
                        color = RMuted, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Text(r.label, color = r.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------- Workout report ----------

private fun minutesText(m: Int): String = if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"

@Composable
fun WorkoutReportSection(workouts: WorkoutStore, settings: AppSettings) {
    PeriodReportCard(
        title = "Workout report",
        periods = Period.values().toList(),
        refreshKey = Pair(workouts.minutesToday, workouts.workoutsToday.size),
        today = workoutDayNow(),
        totalsBetween = { s, e -> workouts.totalsBetween(s, e) },
        goalPerDay = settings.workoutGoalMin,
        format = { minutesText(it) },
        accent = Color(0xFF81C784),
        yearlyAverage = false,
        rating = ::hydrationFromRatio,
        dayDetail = { d ->
            workouts.workoutsForDay(d).map {
                it.type to ("${it.minutes} min · ${it.calories} kcal" +
                        if (it.tracked) " · %.2f km".format(it.distanceM / 1000.0) else "")
            }
        },
        dayExtra = { d -> WorkoutTrackDetails(workouts.workoutsForDay(d)) }
    )
}