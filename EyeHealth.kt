package com.example.fittrack

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
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
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

private val ECard = Color(0xFF1A1D23)
private val EMuted = Color(0xFF9AA0AA)
private val EBody = Color(0xFFD0D3D9)
private val Teal = Color(0xFF4DD0E1)

private class ScreenUse(val totalMs: Long, val top: List<Pair<String, Long>>)   // package, ms

private fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

// Adds up the time each app was on screen since 12:00 am
private fun readScreenUse(ctx: Context): ScreenUse {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val start = ResetTimes.startMs("eye")   // starts again at the eye health reset time (default 5:00 am)
    val events = usm.queryEvents(start, now)
    val e = UsageEvents.Event()
    val open = HashMap<String, Long>()
    val totals = HashMap<String, Long>()
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        val pkg = e.packageName ?: continue
        @Suppress("DEPRECATION")
        when (e.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> open[pkg] = e.timeStamp
            UsageEvents.Event.MOVE_TO_BACKGROUND ->
                open.remove(pkg)?.let { totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - it) }
        }
    }
    open.forEach { (pkg, t) -> totals[pkg] = (totals[pkg] ?: 0L) + (now - t) }   // still open now
    val used = totals.filterKeys { !it.contains("launcher") && !it.contains("systemui") }
    return ScreenUse(used.values.sum(), used.entries.sortedByDescending { it.value }.map { it.key to it.value })
}

private val KNOWN = mapOf(
    "com.facebook.katana" to "Facebook",
    "com.facebook.lite" to "Facebook Lite",
    "com.facebook.orca" to "Messenger",
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.ss.android.ugc.trill" to "TikTok",
    "com.whatsapp" to "WhatsApp",
    "com.snapchat.android" to "Snapchat",
    "com.twitter.android" to "X",
    "org.telegram.messenger" to "Telegram",
    "com.android.chrome" to "Chrome"
)

private fun appName(ctx: Context, pkg: String): String {
    KNOWN[pkg]?.let { return it }
    return try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}

private fun dur(ms: Long): String {
    val m = (ms / 60000).toInt()
    return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

private fun level(hours: Float): Triple<String, Color, Int> = when {
    hours < 2f -> Triple("Eyes are happy", Color(0xFF00E5A0), 0)
    hours < 4f -> Triple("Getting a bit high", Color(0xFFFFB74D), 1)
    hours < 6f -> Triple("High screen time", Color(0xFFFF8A65), 2)
    else -> Triple("Excessive screen time", Color(0xFFEF5350), 3)
}

private fun funnyLine(topPkg: String?, lvl: Int, seed: Int): String {
    fun pick(list: List<String>) = list[seed % list.size]
    if (lvl == 0) return pick(listOf(
        "Your eyes say thank you. Keep it up!",
        "Low screen time today. Your eyeballs are doing a happy dance.",
        "Nice! The real world is still there, looking good."
    ))
    val byApp = when (topPkg) {
        "com.facebook.katana", "com.facebook.lite", "com.instagram.android" -> listOf(
            "Facebook / Reels again? Your thumb needs a vacation, not just your eyes.",
            "One more reel won't change your life. Blink, please!",
            "Your eyes asked me to say: they didn't sign up for a Reels marathon.",
            "Scrolling reels is not a sport, but your thumb is training like an athlete."
        )
        "com.google.android.youtube" -> listOf(
            "YouTube 'just one more video' has been going on for hours.",
            "The YouTube algorithm knows you well, but your eyes want a break."
        )
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> listOf(
            "TikTok called. Even it thinks you've had enough.",
            "Short videos, long screen time. Your eyes noticed."
        )
        "com.whatsapp", "com.facebook.orca", "org.telegram.messenger" -> listOf(
            "Chatting non-stop? Your friends will still be there after a 5-minute break.",
            "Your eyes want to reply too: 'Please rest.'"
        )
        else -> emptyList()
    }
    val generic = if (lvl >= 3) listOf(
        "Your screen got more attention than your family today. Look away for a bit!",
        "Your eyes are filing a complaint. Please look at something far away.",
        "Screen time is through the roof. Even your phone wants you to go outside."
    ) else listOf(
        "Time for the 20-20-20 rule: every 20 minutes, look 20 feet away for 20 seconds.",
        "Your eyes have been working overtime. Give them a coffee break.",
        "Blink more! Your eyes are drier than a desert right now."
    )
    return pick(if (byApp.isNotEmpty() && lvl >= 1) byApp else generic)
}

@Composable
fun EyeHealthCard() {
    val ctx = LocalContext.current
    remember { ResetTimes.init(ctx) }
    var allowed by remember { mutableStateOf(hasUsageAccess(ctx)) }
    var use by remember { mutableStateOf<ScreenUse?>(null) }

    // Re-checks every 10 seconds, so it updates after you grant access and as you use the phone
    LaunchedEffect(Unit) {
        while (true) {
            allowed = hasUsageAccess(ctx)
            use = if (allowed) runCatching { readScreenUse(ctx) }.getOrNull() else null
            delay(10_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ECard.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Eye Health", color = EMuted, fontSize = 14.sp)
        val u = use
        if (!allowed || u == null) {
            Text(
                "To measure your screen time, allow \"Usage access\" for FitTrack. " +
                        "Your data stays on this phone.",
                color = EBody, fontSize = 13.sp
            )
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black)
            ) { Text("Allow usage access") }
            return@Column
        }
        val hours = u.totalMs / 3_600_000f
        val (label, color, lvl) = level(hours)
        val seed = LocalDate.now().dayOfYear + java.time.LocalTime.now().hour
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dur(u.totalMs), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("Screen time today · resets at ${ResetTimes.label(ResetTimes.minutes("eye"))}", color = EMuted, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { (hours / 6f).coerceIn(0f, 1f) },
            color = color, trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
        Text(funnyLine(u.top.firstOrNull()?.first, lvl, seed), color = EBody, fontSize = 13.sp)
        u.top.take(3).forEach { (pkg, ms) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(appName(ctx, pkg), color = EMuted, fontSize = 12.sp)
                Text(dur(ms), color = EMuted, fontSize = 12.sp)
            }
        }
    }
}
