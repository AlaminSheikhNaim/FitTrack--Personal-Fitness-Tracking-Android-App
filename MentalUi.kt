package com.example.fittrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val MCard = Color(0xFF1A1D23)
private val MInner = Color(0xFF22262E)
private val MMuted = Color(0xFF9AA0AA)
private val MBody = Color(0xFFD0D3D9)
val MentalPurple = Color(0xFF9575CD)

private fun moodColor(m: Float): Color = when {
    m >= 4.5f -> Color(0xFF00E5A0)
    m >= 3.5f -> Color(0xFF8BD17C)
    m >= 2.5f -> Color(0xFFFFD54F)
    m >= 1.5f -> Color(0xFFFF8A65)
    else -> Color(0xFFE57373)
}

private fun scoreColor(s: Int): Color = when {
    s >= 80 -> Color(0xFF00E5A0)
    s >= 65 -> Color(0xFF8BD17C)
    s >= 50 -> Color(0xFFFFD54F)
    s >= 35 -> Color(0xFFFF8A65)
    else -> Color(0xFFE57373)
}

private fun ratingLabel(s: Int): String = when {
    s >= 80 -> "Excellent"
    s >= 65 -> "Good"
    s >= 50 -> "Fair"
    s >= 35 -> "Low"
    else -> "Needs care"
}

private enum class MPeriod(val label: String, val days: Int) {
    DAILY("Daily", 1), WEEKLY("Weekly", 7), MONTHLY("Monthly", 30), YEARLY("Yearly", 365)
}

private class DayData(
    val date: LocalDate, val mood: MoodEntry?, val sleepMin: Int, val sleepQ: Int?,
    val kcal: Int, val waterMl: Int, val workoutMin: Int, val steps: Int, val prayers: Int
) {
    val active get() = mood != null || sleepMin > 0 || kcal > 0 || waterMl > 0 ||
            workoutMin > 0 || steps > 0 || prayers > 0
}

private class Factor(val name: String, val value: String, val score: Int?, val note: String? = null)

private class MentalReport(
    val rating: Int?, val avgMood: Float?, val checkins: Int,
    val bars: List<Pair<String, Float?>>, val factors: List<Factor>,
    val comments: List<String>, val tips: List<String>
)

private fun hmText(min: Int) = if (min <= 0) "—" else "${min / 60}h ${min % 60}m"

private fun avgOrNull(l: List<Int>): Double? = if (l.isEmpty()) null else l.average()

private fun buildReport(
    start: LocalDate, end: LocalDate, period: MPeriod,
    mental: MentalStore, tracker: StepTracker, water: WaterStore, food: FoodStore,
    workouts: WorkoutStore, sleep: SleepStore, prayer: PrayerStore, settings: AppSettings,
    quality: Map<LocalDate, Int>
): MentalReport {
    val moods = mental.between(start, end)
    val steps = tracker.totalsBetween(start, end)
    val wat = water.totalsBetween(start, end)
    val kcal = food.totalsBetween(start, end)
    val wk = workouts.totalsBetween(start, end)
    val slp = sleep.totalsBetween(start, end)
    val pr = prayer.totalsBetween(start, end)

    val days = ArrayList<DayData>()
    var d = start
    while (!d.isAfter(end)) {
        days.add(
            DayData(d, moods[d], slp[d] ?: 0, quality[d], kcal[d] ?: 0, wat[d] ?: 0,
                wk[d] ?: 0, steps[d] ?: 0, pr[d] ?: 0)
        )
        d = d.plusDays(1)
    }
    val active = days.filter { it.active }
    val withMood = days.filter { it.mood != null }
    val moodVals = withMood.map { it.mood!!.mood }
    val avgMood = if (moodVals.isEmpty()) null else moodVals.average().toFloat()

    // ---- factors (each scored 0..100) ----
    val sleepDays = active.filter { it.sleepMin > 0 }
    val foodDays = active.filter { it.kcal > 0 }
    val qDays = active.filter { it.sleepQ != null }
    val workGoal = minOf(settings.workoutGoalMin, 30).coerceAtLeast(1)
    val factors = ArrayList<Factor>()

    avgOrNull(sleepDays.map { it.sleepMin })?.let { m ->
        factors.add(Factor("Sleep", hmText(m.roundToInt()),
            sleepDays.map { sleepScore(it.sleepMin, settings.sleepGoalMin) }.average().roundToInt()))
    } ?: factors.add(Factor("Sleep", "not logged", null))
    avgOrNull(qDays.map { it.sleepQ!! })?.let {
        factors.add(Factor("Sleep quality", "${it.roundToInt()} / 100", it.roundToInt()))
    }
    avgOrNull(foodDays.map { it.kcal })?.let { k ->
        factors.add(Factor("Food", "${k.roundToInt()} kcal",
            foodDays.map { foodScore(it.kcal, settings.calorieGoal) }.average().roundToInt()))
    } ?: factors.add(Factor("Food", "not logged", null))
    if (active.isNotEmpty()) {
        val w = active.map { it.waterMl }.average()
        factors.add(Factor("Water", formatWater(w.roundToInt()),
            active.map { minOf(it.waterMl.toFloat() / settings.waterGoalMl.coerceAtLeast(1), 1f) * 100 }
                .average().roundToInt()))
        val wm = active.map { it.workoutMin }.average()
        factors.add(Factor("Workout", "${wm.roundToInt()} min",
            active.map { minOf(it.workoutMin.toFloat() / workGoal, 1f) * 100 }.average().roundToInt()))
        val st = active.map { it.steps }.average()
        factors.add(Factor("Steps", "%,d".format(st.roundToInt()),
            active.map { minOf(it.steps.toFloat() / settings.stepGoal.coerceAtLeast(1), 1f) * 100 }
                .average().roundToInt()))
        val p = active.map { it.prayers }.average()
        factors.add(Factor("Prayer", "%.1f / 5".format(p),
            active.map { it.prayers * 20 }.average().roundToInt()))
    }
    val scored = factors.mapNotNull { it.score }
    val factorAvg = if (scored.isEmpty()) null else scored.average()

    val rating = avgMood?.let {
        val moodScore = ((it - 1f) / 4f * 100f).toDouble()
        (if (factorAvg == null) moodScore else 0.55 * moodScore + 0.45 * factorAvg).roundToInt()
    }

    // ---- bars ----
    val bars: List<Pair<String, Float?>> = when (period) {
        MPeriod.DAILY -> emptyList()
        MPeriod.WEEKLY -> days.map { it.date.dayOfWeek.name.take(1) to it.mood?.mood?.toFloat() }
        MPeriod.MONTHLY -> days.map { it.date.dayOfMonth.toString() to it.mood?.mood?.toFloat() }
        MPeriod.YEARLY -> {
            val ym = YearMonth.from(end)
            (11 downTo 0).map { i ->
                val m = ym.minusMonths(i.toLong())
                val vals = withMood.filter { YearMonth.from(it.date) == m }.map { it.mood!!.mood }
                m.month.name.take(1) to (if (vals.isEmpty()) null else vals.average().toFloat())
            }
        }
    }

    // ---- commentary ----
    val comments = ArrayList<String>()
    val low = withMood.filter { it.mood!!.mood <= 2 }
    val happy = withMood.filter { it.mood!!.mood >= 4 }
    if (avgMood == null) {
        comments.add("No mood check-in in this period yet. Answer the 8:30 pm question each day and this report will connect your mood with your sleep, food, water, activity and prayer.")
    } else {
        if (period == MPeriod.DAILY) {
            val e = withMood.first().mood!!
            comments.add("You felt ${MOOD_LABELS[e.mood - 1].lowercase()} today" +
                    (if (e.reasons.isNotEmpty()) " — mainly because of: ${e.reasons.joinToString(", ")}." else "."))
        } else {
            comments.add("Your mood averaged ${"%.1f".format(avgMood)} / 5 (${MOOD_LABELS[avgMood.roundToInt() - 1].lowercase()}) over ${withMood.size} check-in(s): ${happy.size} happy day(s) and ${low.size} low day(s).")
            fun top(list: List<DayData>) = list.flatMap { it.mood!!.reasons }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(3).map { it.key }
            if (low.isNotEmpty() && top(low).isNotEmpty())
                comments.add("Most common reasons on low days: ${top(low).joinToString(", ")}.")
            if (happy.isNotEmpty() && top(happy).isNotEmpty())
                comments.add("What lifted you most: ${top(happy).joinToString(", ")}.")
        }
        if (period != MPeriod.DAILY) {
            fun cmp(pool: List<DayData>, test: (DayData) -> Boolean, good: String, bad: String) {
                val a = pool.filter(test); val b = pool.filterNot(test)
                if (a.size < 2 || b.size < 2) return
                val ma = a.map { it.mood!!.mood }.average(); val mb = b.map { it.mood!!.mood }.average()
                if (ma - mb >= 0.5) comments.add("On days $good your mood averaged ${"%.1f".format(ma)} vs ${"%.1f".format(mb)} on days $bad.")
            }
            val sg = settings.sleepGoalMin
            cmp(withMood.filter { it.sleepMin > 0 }, { it.sleepMin >= sg * 0.88 }, "you slept enough", "you slept less")
            cmp(withMood, { it.workoutMin >= 20 }, "you exercised 20+ min", "you did not")
            cmp(withMood, { it.prayers >= 4 }, "you prayed 4–5 times", "you prayed less")
            cmp(withMood, { it.waterMl >= settings.waterGoalMl * 0.8 }, "you drank enough water", "you drank less")
            cmp(withMood, { it.steps >= settings.stepGoal * 0.7 }, "you walked well", "you walked less")
        }
        val weak = factors.filter { (it.score ?: 100) < 60 }
        if (weak.isNotEmpty())
            comments.add("Lifestyle areas pulling your wellbeing down: ${weak.joinToString(", ") { it.name.lowercase() + " (" + it.score + ")" }}.")
        else if (scored.isNotEmpty())
            comments.add("Your daily habits are well balanced, which supports a steady mood.")
    }
    val recent = withMood.takeLast(5)
    val concerning = (recent.size >= 3 && recent.count { it.mood!!.mood <= 2 } >= 3) ||
            (avgMood != null && avgMood <= 2.2f && withMood.size >= 3)
    if (concerning || (rating != null && rating < 50)) {
        comments.add("You are not alone: the NIMH National Mental Health Survey (2018–19) found about 1 in 6 Bangladeshi adults live with a mental health condition, and around 92% never receive treatment. Reaching out early is normal, not weakness.")
    }

    // ---- suggestions ----
    val tips = ArrayList<String>()
    val reasonsAll = low.flatMap { it.mood!!.reasons }.toSet() +
            (if (period == MPeriod.DAILY) withMood.flatMap { it.mood!!.reasons } else emptyList())
    fun has(s: String) = reasonsAll.any { it.contains(s, ignoreCase = true) }
    if (has("Loneliness")) tips.add("Call or meet one person you trust today — even 10 minutes of real conversation helps with loneliness.")
    if (has("Stress")) tips.add("Break tomorrow's work/study into 3 small tasks and do the hardest first; take a 5-minute break every hour.")
    if (has("Overthinking")) tips.add("Write your worries on paper, then pick one action you can take. Slow breathing (4 seconds in, 6 out) for 3 minutes calms the mind.")
    if (has("Money")) tips.add("List your fixed expenses and one small step to improve things; talking it over with family often reduces the weight.")
    if (has("Family") || has("Relationship")) tips.add("Choose a calm time to talk and listen first. If it stays heavy, a counsellor or elder you respect can help.")
    if (has("Health")) tips.add("If a health worry keeps returning, book a check-up — certainty is usually calmer than waiting.")
    factors.filter { (it.score ?: 100) < 65 }.forEach {
        when (it.name) {
            "Sleep" -> tips.add("Aim for 7–9 hours (adult guidance from WHO and sleep medicine). Keep a fixed bedtime and put the phone away 30 minutes before sleep.")
            "Sleep quality" -> tips.add("Keep the room dark, quiet and cool, and avoid tea/coffee after the afternoon for better sleep quality.")
            "Food" -> tips.add("Eat regular balanced meals within your calorie target; skipped meals and heavy overeating both disturb mood and energy.")
            "Water" -> tips.add("Even mild dehydration affects mood and focus. Sip water through the day and finish your daily target.")
            "Workout" -> tips.add("A 20–30 minute brisk walk or workout lifts mood. WHO advises at least 150 minutes of moderate activity a week.")
            "Steps" -> tips.add("Add short walks after meals to reach your step goal; walking outdoors in daylight also helps mood.")
            "Prayer" -> tips.add("Keep your prayer routine — it gives calm and structure to the day. Take a quiet minute of dhikr or deep breathing after each prayer.")
        }
    }
    if (avgMood != null && avgMood >= 3.5f) tips.add("Notice what made your good days good and repeat one of those things tomorrow.")
    if (avgMood != null && avgMood < 3f && tips.isEmpty()) tips.add("Plan one small enjoyable thing tomorrow — a walk, a call, a favourite meal.")
    if (concerning || (rating != null && rating < 35)) {
        tips.add("If low mood lasts more than two weeks, speak with a doctor or counsellor — the National Institute of Mental Health (NIMH), Dhaka offers care. If you ever feel unsafe or think of harming yourself, contact emergency services or someone you trust right now.")
    }
    if (avgMood == null) tips.add("Your first check-in unlocks a personal rating and tailored suggestions.")

    return MentalReport(rating, avgMood, withMood.size, bars, factors, comments, tips.distinct().take(6))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MentalDetail(
    mental: MentalStore, tracker: StepTracker, water: WaterStore, food: FoodStore,
    workouts: WorkoutStore, sleep: SleepStore, prayer: PrayerStore, settings: AppSettings,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mood by remember(mental.version) { mutableIntStateOf(mental.todayMood) }
    val picked = remember(mental.version) { mutableStateListOf<String>().apply { addAll(mental.todayReasons) } }
    // After a check-in is saved (or if already done today) the mood options fold away
    var expanded by remember(mental.version) { mutableStateOf(mental.todayMood == 0) }
    var period by remember { mutableStateOf(MPeriod.DAILY) }
    var offset by remember { mutableIntStateOf(0) }

    DetailScreen("Mental Health", onBack) {
        // ---------- check-in ----------
        Column(
            modifier = Modifier.fillMaxWidth().background(MCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!expanded && mental.todayMood > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(MOOD_FACES[mental.todayMood - 1], fontSize = 28.sp)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Today: ${MOOD_LABELS[mental.todayMood - 1]}", color = Color.White,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (mental.todayReasons.isNotEmpty())
                            Text(mental.todayReasons.joinToString(", "), color = MMuted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { expanded = true }) { Text("Change", color = MentalPurple) }
                }
            } else {
            Text("How are you feeling today?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (mental.todayMood > 0) "Today's check-in is saved. You can change it until the day resets."
                else "You are asked every day at 8:30 pm. Pick your mood, then the reasons.",
                color = MMuted, fontSize = 12.sp
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1..5) {
                    val sel = mood == i
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) moodColor(i.toFloat()).copy(alpha = 0.25f) else MInner)
                            .clickable { if (mood != i) { mood = i; picked.clear() } }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(MOOD_FACES[i - 1], fontSize = 26.sp)
                        Text(MOOD_LABELS[i - 1], color = if (sel) Color.White else MMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            if (mood > 0) {
                Text(
                    if (mood >= 4) "What made you feel good? (select any)"
                    else if (mood <= 2) "What is behind this feeling? (select any)"
                    else "What describes your day? (select any)",
                    color = MBody, fontSize = 14.sp
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reasonsFor(mood).forEach { r ->
                        FilterChip(
                            selected = r in picked,
                            onClick = { if (r in picked) picked.remove(r) else picked.add(r) },
                            label = { Text(r, fontSize = 12.sp) }
                        )
                    }
                }
                Button(
                    onClick = { mental.save(mood, picked.toList()) },
                    colors = ButtonDefaults.buttonColors(containerColor = MentalPurple, contentColor = Color.Black)
                ) { Text(if (mental.todayMood > 0) "Update check-in" else "Save check-in") }
            }
            }
        }

        // ---------- report (all details live here, not on the dashboard) ----------
        val today = mental.today()
        val date = today.minusDays(offset.toLong())
        val start = if (period == MPeriod.DAILY) date else today.minusDays((period.days - 1).toLong())
        val end = if (period == MPeriod.DAILY) date else today
        val report = remember(period, offset, mental.version) {
            val q = HashMap<LocalDate, Int>()
            runCatching {
                MonitorStore.get(context).reports.filter { !it.test && it.score >= 0 }.forEach {
                    val day = LocalDate.parse(it.day)
                    q[day] = maxOf(q[day] ?: 0, it.score)
                }
            }
            buildReport(start, end, period, mental, tracker, water, food, workouts, sleep, prayer, settings, q)
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(MCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Mood report", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MPeriod.values().forEach { p ->
                    FilterChip(selected = period == p, onClick = { period = p; offset = 0 },
                        label = { Text(p.label, fontSize = 12.sp) })
                }
            }
            val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
            if (period == MPeriod.DAILY) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { offset += 1 }) { Text("‹", color = Color.White, fontSize = 22.sp) }
                    Text(date.format(fmt), color = MBody, fontSize = 14.sp)
                    TextButton(onClick = { if (offset > 0) offset -= 1 }, enabled = offset > 0) {
                        Text("›", color = if (offset > 0) Color.White else MMuted, fontSize = 22.sp)
                    }
                }
            } else {
                Text("${start.format(fmt)} – ${end.format(fmt)}", color = MMuted, fontSize = 12.sp)
            }

            // Rating
            val r = report.rating
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MInner).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Mental condition", color = MMuted, fontSize = 12.sp)
                if (r == null) {
                    Text("Not rated yet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Needs at least one mood check-in", color = MMuted, fontSize = 12.sp)
                } else {
                    Text("$r / 100", color = scoreColor(r), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(ratingLabel(r), color = scoreColor(r), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    report.avgMood?.let {
                        Text("Average mood ${"%.1f".format(it)} / 5 · ${report.checkins} check-in(s)",
                            color = MMuted, fontSize = 12.sp)
                    }
                }
            }

            // Mood chart
            if (period == MPeriod.DAILY) {
                val e = mental.entry(date)
                if (e != null) Text("${MOOD_FACES[e.mood - 1]}  ${MOOD_LABELS[e.mood - 1]}", color = Color.White, fontSize = 18.sp)
            } else {
                Text("Mood trend", color = MMuted, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(
                    if (report.bars.size > 12) 2.dp else 6.dp), verticalAlignment = Alignment.Bottom) {
                    report.bars.forEach { (_, v) ->
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                            Box(modifier = Modifier.fillMaxWidth()
                                .fillMaxHeight(if (v == null) 0.04f else v / 5f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (v == null) Color(0xFF2A2E36) else moodColor(v)))
                        }
                    }
                }
                if (period != MPeriod.MONTHLY) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        report.bars.forEach { (l, _) ->
                            Text(l, color = MMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Factors with bars
            Text(if (period == MPeriod.DAILY) "That day's habits" else "Average habits", color = MMuted, fontSize = 12.sp)
            report.factors.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(f.name, color = MBody, fontSize = 14.sp)
                        Text(f.value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (f.score ?: 0) / 100f },
                        color = scoreColor(f.score ?: 0), trackColor = Color(0xFF2A2E36),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (period == MPeriod.DAILY) {
                val done = prayer.dayEntries(date).filter { it.slot < TAHAJJUD_SLOT }.map { it.slot }.toSet()
                val tah = prayer.dayEntries(date).any { it.slot == TAHAJJUD_SLOT }
                Text(
                    PRAYER_NAMES.mapIndexed { i, n -> n + if (i in done) " ✓" else " ✗" }.joinToString("  ·  ") +
                            if (tah) "  ·  Tahajjud ✓" else "",
                    color = MMuted, fontSize = 12.sp
                )
            }

            // Analysis
            Text("Analysis", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            report.comments.forEach { Text("• $it", color = MBody, fontSize = 13.sp) }
            Text("Suggestions", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            report.tips.forEach { Text("• $it", color = MBody, fontSize = 13.sp) }
            Text(
                "This is a wellbeing guide based on your own entries, not a medical diagnosis.",
                color = MMuted, fontSize = 11.sp
            )
        }
    }
}
