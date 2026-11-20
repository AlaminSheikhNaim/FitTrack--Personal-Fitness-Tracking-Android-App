package com.example.fittrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PCard = Color(0xFF1A1D23)
private val PMuted = Color(0xFF9AA0AA)
private val PBody = Color(0xFFD0D3D9)
val PrayerGreen = Color(0xFFAED581)

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))

// Score out of 100: each prayer is worth 20. Tahajjud adds a 10 point bonus (never above 100).
fun prayerScore(count: Int, tahajjud: Boolean): Int =
    minOf(100, count * 20 + if (tahajjud && count > 0) 10 else 0)

private fun prayerLevel(count: Int, tahajjud: Boolean): Pair<String, Color> =
    if (count == 0) Pair(if (tahajjud) "Tahajjud only" else "No prayer yet", Color(0xFF9AA0AA))
    else hydrationLevel(prayerScore(count, tahajjud))

// The more prayers, the better the words. Verse meanings are paraphrased, not exact translations.
private fun prayerMessage(count: Int, seed: Int): String {
    val list = when (count) {
        0 -> listOf(
            "Prayer is prescribed for the believers at fixed times (An-Nisa 4:103). Start with the next one, Insha'Allah.",
            "Do not despair of Allah's mercy (Az-Zumar 39:53). Turn back and pray today."
        )
        1 -> listOf(
            "A good start! Keep prayer established at both ends of the day and in the early night; good deeds remove bad deeds (Hud 11:114).",
            "Seek help through patience and prayer; Allah is with the patient (Al-Baqarah 2:153)."
        )
        2 -> listOf(
            "Establish prayer for My remembrance, says Allah (Ta-Ha 20:14). Keep going!",
            "Seek help through patience and prayer (Al-Baqarah 2:45). Three more to complete the day."
        )
        3 -> listOf(
            "Prayer keeps a person away from shameful and evil deeds (Al-Ankabut 29:45). Two more to go!",
            "Guard your prayers and stand before Allah with humility (Al-Baqarah 2:238)."
        )
        4 -> listOf(
            "Almost complete! Guard the prayers, especially the middle prayer (Al-Baqarah 2:238). Just one more.",
            "Prayer keeps one far from shameful and wrong deeds (Al-Ankabut 29:45). Don't miss the last one."
        )
        else -> listOf(
            "Masha'Allah! Successful indeed are the believers, those who are humble in their prayers (Al-Mu'minun 23:1-2).",
            "Masha'Allah, all five done! Those who guard their prayers are the ones who will inherit Paradise (Al-Mu'minun 9-11)."
        )
    }
    return list[seed % list.size]
}

private const val TAHAJJUD_VERSE =
    "You also prayed Tahajjud! Pray part of the night as an extra; your Lord may raise you to a praised station (Al-Isra 17:79)."

// Small square tick box: empty outline when not prayed, green with a ✓ when prayed
@Composable
private fun PrayerTick(done: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(if (done) PrayerGreen else Color.Transparent, RoundedCornerShape(7.dp))
            .border(2.dp, if (done) PrayerGreen else PMuted, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (done) Text("✓", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// One prayer row: name on the left, tick on the right. Tap anywhere on the row to tick or untick.
@Composable
private fun PrayerTickRow(prayer: PrayerStore, slot: Int, showTime: Boolean) {
    val e = prayer.todayEntries.firstOrNull { it.slot == slot }
    val done = e != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { prayer.toggle(slot) })
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            PRAYER_NAMES[slot], color = if (done) Color.White else PBody, fontSize = 15.sp,
            fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (showTime && e != null) {
            Text(clock(e.tsMs), color = PrayerGreen, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
        }
        PrayerTick(done)
    }
}

// Dashboard card (shown when Settings > Prayer card > Enable is on)
@Composable
fun PrayerDashboardCard(prayer: PrayerStore, onOpen: () -> Unit) {
    val count = prayer.count
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PCard.copy(alpha = 0.92f))
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Prayer", color = PMuted, fontSize = 12.sp)
            // Ring of completion (where other cards have their +Add button).
            // All five prayed: the ring is full with a green tick inside.
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { count / 5f }, color = PrayerGreen,
                    trackColor = Color(0xFF2A2E36), strokeWidth = 3.dp,
                    modifier = Modifier.fillMaxSize()
                )
                if (count >= 5) Text("✓", color = PrayerGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            if (count >= 5) "All 5 done" else "$count / 5",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(
            progress = { count / 5f }, color = PrayerGreen,
            trackColor = Color(0xFF2A2E36), modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun PrayerDetail(prayer: PrayerStore, onBack: () -> Unit) {
    val count = prayer.count
    val seed = remember { java.time.LocalDate.now().dayOfYear }

    DetailScreen("Prayer", onBack) {
        // Summary
        Column(
            modifier = Modifier.fillMaxWidth().background(PCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Today's prayers", color = PMuted, fontSize = 14.sp)
            Text("$count / 5", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { count / 5f }, color = PrayerGreen,
                trackColor = Color(0xFF2A2E36), modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Tick each prayer when you have prayed it. " +
                        "Starts again at ${ResetTimes.label(ResetTimes.minutes("prayer"))}.",
                color = PMuted, fontSize = 11.sp
            )
        }

        // The five prayers, each with a tick
        Column(
            modifier = Modifier.fillMaxWidth().background(PCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in 0 until TAHAJJUD_SLOT) PrayerTickRow(prayer, i, showTime = true)
        }

        // Optional Tahajjud
        Column(
            modifier = Modifier.fillMaxWidth().background(PCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tahajjud (optional)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val t = prayer.todayEntries.firstOrNull { it.slot == TAHAJJUD_SLOT }
                    Text(if (t != null) "✓  ${clock(t.tsMs)}" else "Night prayer, adds a bonus to your rating",
                        color = if (t != null) PrayerGreen else PMuted, fontSize = 12.sp)
                }
                if (prayer.tahajjudDone) {
                    TextButton(onClick = { prayer.removeTahajjud() }) { Text("Remove", color = PMuted) }
                } else {
                    Button(
                        onClick = { prayer.addTahajjud() },
                        colors = ButtonDefaults.buttonColors(containerColor = PrayerGreen, contentColor = Color.Black)
                    ) { Text("+Add") }
                }
            }
        }

        // Rating and message
        val (label, color) = prayerLevel(count, prayer.tahajjudDone)
        val score = prayerScore(count, prayer.tahajjudDone)
        Column(
            modifier = Modifier.fillMaxWidth().background(PCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Prayer rating · today", color = PMuted, fontSize = 14.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("$score / 100", color = PBody, fontSize = 14.sp)
            }
            Text("★".repeat(count) + "☆".repeat(5 - count), color = color, fontSize = 18.sp)
            Text(prayerMessage(count, seed), color = PBody, fontSize = 13.sp)
            if (prayer.tahajjudDone) Text(TAHAJJUD_VERSE, color = PrayerGreen, fontSize = 13.sp)
            Text("Verse meanings are paraphrased, not exact translations.", color = PMuted, fontSize = 10.sp)
        }

        PrayerReportSection(prayer)
    }
}

// Report: every bar is tappable. The history is stored on the phone.
@Composable
fun PrayerReportSection(prayer: PrayerStore) {
    val today = ResetTimes.dayNow("prayer")
    PeriodReportCard(
        title = "Prayer report",
        periods = Period.values().toList(),
        refreshKey = Pair(prayer.count, prayer.tahajjudDone),
        today = today,
        totalsBetween = { s, e -> prayer.totalsBetween(s, e) },
        goalPerDay = 5,
        format = { "$it prayers" },
        accent = PrayerGreen,
        yearlyAverage = true,
        rating = ::hydrationFromRatio,
        dayDetail = { d ->
            prayer.dayEntries(d).map { e ->
                (if (e.slot == TAHAJJUD_SLOT) "Tahajjud" else PRAYER_NAMES[e.slot]) to clock(e.tsMs)
            }
        }
    )

    val recent = remember(prayer.count, prayer.tahajjudDone) {
        (1..6).map { back ->
            val d = today.minusDays(back.toLong())
            Pair(d, prayer.dayEntries(d))
        }.filter { it.second.isNotEmpty() }
    }
    if (recent.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().background(PCard, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent days", color = PMuted, fontSize = 14.sp)
            recent.forEach { (d, entries) ->
                val n = entries.count { it.slot < TAHAJJUD_SLOT }
                val t = entries.any { it.slot == TAHAJJUD_SLOT }
                val (label, color) = prayerLevel(n, t)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE d MMM")), color = PBody, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("$n/5" + if (t) " + Tahajjud" else "", color = PMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}