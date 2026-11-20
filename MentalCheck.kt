package com.example.fittrack

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.ZoneId

// Every day at 8:30 pm: "How are you feeling today?"
object MentalCheck {
    private const val CHANNEL = "mental_checkin"
    private const val NOTIFICATION_ID = 78
    const val ACTION = "com.example.fittrack.MENTAL_CHECK"
    const val EXTRA_OPEN = "open_mental"
    const val ASK_HOUR = 20
    const val ASK_MINUTE = 30

    private fun alarmIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 11,
            Intent(context, MentalReceiver::class.java).setAction(ACTION),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleDaily(context: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(ASK_HOUR, ASK_MINUTE)
        if (!at.isAfter(now)) at = at.plusDays(1)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!
        )
    }

    fun ensureScheduled(context: Context) {
        if (alarmIntent(context, PendingIntent.FLAG_NO_CREATE) == null) scheduleDaily(context)
    }

    fun notifyAsk(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mental health check-in", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 12,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "How are you feeling today? Take a moment to check in."
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mental Health check-in")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, b.build())
        } catch (e: SecurityException) { }
    }
}

class MentalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                MentalCheck.scheduleDaily(app)
            MentalCheck.ACTION -> {
                val store = MentalStore(app)
                val answered = store.todayMood > 0
                store.close()
                if (!answered) MentalCheck.notifyAsk(app)
                MentalCheck.scheduleDaily(app)
            }
        }
    }
}
