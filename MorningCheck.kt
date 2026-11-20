package com.example.fittrack

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Sleep guessed from phone use alone (no Sleep Monitor needed)
class PassiveEstimate(val startMs: Long, val endMs: Long)

object PassiveSleep {

    private class Span(val start: Long, val end: Long)

    // "Usage access" is switched on by the person in Settings
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Looks at when the screen was on or off since yesterday evening. The longest rest of the
    // night with no phone use is taken as sleep. Short screen wake-ups (notifications) are
    // ignored, and a short time awake in the middle of the night does not split the night.
    // Returns null if the person is probably still asleep, or there is not enough data.
    fun estimate(context: Context, nowMs: Long = System.currentTimeMillis()): PassiveEstimate? {
        if (!hasAccess(context)) return null
        val zone = ZoneId.systemDefault()
        val windowStart = LocalDate.now(zone).minusDays(1).atTime(18, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(windowStart - 3_600_000L, nowMs)
        } catch (e: Exception) {
            return null
        }

        val screen = ArrayList<Span>()
        val appSwitches = ArrayList<Long>()
        var onSince = -1L
        var sawScreenEvents = false
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                15 -> {   // screen turned on (interactive)
                    sawScreenEvents = true
                    if (onSince < 0) onSince = e.timeStamp
                }
                16 -> {   // screen turned off
                    sawScreenEvents = true
                    if (onSince >= 0) screen.add(Span(onSince, e.timeStamp))
                    onSince = -1L
                }
                1 -> appSwitches.add(e.timeStamp)   // an app came to the front
            }
        }
        // The screen is on right now (for example the person just opened the app)
        if (onSince >= 0) screen.add(Span(onSince, nowMs))

        // Times the phone was really used: screen on for at least a minute, or being used now
        val uses = ArrayList<Span>()
        if (sawScreenEvents) {
            for (s in screen) if (s.end - s.start >= 60_000L || s.end == nowMs) uses.add(s)
        } else {
            // Older phones: only app switches are known
            for (t in appSwitches) uses.add(Span(t, t + 120_000L))
        }
        uses.sortBy { it.start }

        // Rests of at least 90 minutes between two uses
        val gaps = ArrayList<Span>()
        var lastEnd = -1L
        for (u in uses) {
            if (lastEnd >= 0 && u.start - lastEnd >= 90 * 60_000L) gaps.add(Span(lastEnd, u.start))
            if (u.end > lastEnd) lastEnd = u.end
        }

        // Join rests that are only split by a short time awake (45 minutes or less)
        val blocks = ArrayList<Span>()
        var current: Span? = null
        for (g in gaps) {
            val c = current
            if (c != null && g.start - c.end <= 45 * 60_000L) {
                current = Span(c.start, g.end)
            } else {
                if (c != null) blocks.add(c)
                current = g
            }
        }
        current?.let { blocks.add(it) }

        // A night: starts in the evening or at night, lasts 3 to 14 hours, and has ended
        val best = blocks.filter {
            val hour = Instant.ofEpochMilli(it.start).atZone(zone).hour
            (hour >= 18 || hour < 5) && it.end <= nowMs &&
                    it.end - it.start in (3 * 3_600_000L)..(14 * 3_600_000L)
        }.maxByOrNull { it.end - it.start } ?: return null

        // People fall asleep about 15 minutes after putting the phone down
        return PassiveEstimate(best.start + 15 * 60_000L, best.end)
    }
}

// The morning notification: asks about last night's sleep, with the estimate if there is one
object MorningCheck {
    private const val CHANNEL = "morning_sleep"
    private const val NOTIFICATION_ID = 77
    const val ACTION_MORNING = "com.example.fittrack.MORNING_CHECK"
    const val ACTION_CONFIRM = "com.example.fittrack.MORNING_CONFIRM"

    // First asked at this time. If you are still asleep it tries again every 30 minutes.
    const val ASK_HOUR = 8
    const val GIVE_UP_HOUR = 12

    private fun alarmIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 7,
            Intent(context, MorningReceiver::class.java).setAction(ACTION_MORNING),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    private fun setAlarm(context: Context, atMs: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, atMs, alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!
        )
    }

    // Next check: today at ASK_HOUR if that is still ahead, otherwise tomorrow
    fun scheduleDaily(context: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(ASK_HOUR, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        setAlarm(context, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun scheduleRetry(context: Context) {
        setAlarm(context, System.currentTimeMillis() + 30 * 60_000L)
    }

    // Used when the app opens: only sets the alarm if there is none yet
    fun ensureScheduled(context: Context) {
        if (alarmIntent(context, PendingIntent.FLAG_NO_CREATE) == null) scheduleDaily(context)
    }

    private fun timeText(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

    private fun builder(context: Context): NotificationCompat.Builder {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Morning sleep check", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 8,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Good morning")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun show(context: Context, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, b.build())
        } catch (e: SecurityException) {
            // Notifications are not allowed on this phone
        }
    }

    fun notifyEstimate(context: Context, startMs: Long, endMs: Long) {
        val minutes = ((endMs - startMs) / 60000).toInt()
        val text = "You slept about ${minutes / 60}h ${minutes % 60}m " +
                "(${timeText(startMs)} - ${timeText(endMs)}), estimated from your phone use. " +
                "Is this right?"
        val confirm = PendingIntent.getBroadcast(
            context, 9,
            Intent(context, MorningReceiver::class.java).setAction(ACTION_CONFIRM)
                .putExtra("start", startMs).putExtra("end", endMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = builder(context)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "Confirm", confirm)
        // "Edit" opens the app, where the question lets you change the times
        b.addAction(0, "Edit", builder(context).build().contentIntent)
        show(context, b)
    }

    fun notifyAsk(context: Context) {
        val text = "How did you sleep last night? Tap to enter your sleep time."
        show(context, builder(context).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)))
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                MorningCheck.scheduleDaily(app)

            MorningCheck.ACTION_CONFIRM -> {
                val start = intent.getLongExtra("start", 0L)
                val end = intent.getLongExtra("end", 0L)
                if (start > 0L && end > start) {
                    val sleep = SleepStore(app)
                    sleep.addSession(SleepType.NIGHT, start, end, SleepSource.ESTIMATED)
                    sleep.close()
                    markAsked(app)
                }
                MorningCheck.cancel(app)
            }

            MorningCheck.ACTION_MORNING -> {
                val pending = goAsync()
                Thread {
                    try {
                        morningCheck(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun markAsked(context: Context) {
        context.getSharedPreferences("morning_prompt", Context.MODE_PRIVATE)
            .edit().putString("day", LocalDate.now().toString()).apply()
    }

    private fun morningCheck(context: Context) {
        val asked = context.getSharedPreferences("morning_prompt", Context.MODE_PRIVATE)
            .getString("day", "") == LocalDate.now().toString()
        val sleep = SleepStore(context)
        val logged = sleep.nightMinutesToday > 0 || sleep.sleepingSinceMs > 0L
        sleep.close()
        if (asked || logged) {
            MorningCheck.scheduleDaily(context)
            return
        }

        // The Sleep Monitor's estimate is best. Otherwise use the phone-use estimate.
        val monitor = MonitorStore.get(context).wakeEstimate
        val passive = if (monitor == null) PassiveSleep.estimate(context) else null
        val start = monitor?.startMs ?: passive?.startMs
        val end = monitor?.endMs ?: passive?.endMs

        if (start != null && end != null) {
            MorningCheck.notifyEstimate(context, start, end)
            MorningCheck.scheduleDaily(context)
            return
        }
        // No estimate yet: probably still asleep. Try again every 30 minutes until noon.
        val retryLimit = LocalTime.of(MorningCheck.GIVE_UP_HOUR, 0).minusMinutes(30)
        if (PassiveSleep.hasAccess(context) && LocalTime.now().isBefore(retryLimit)) {
            MorningCheck.scheduleRetry(context)
            return
        }
        MorningCheck.notifyAsk(context)
        MorningCheck.scheduleDaily(context)
    }
}