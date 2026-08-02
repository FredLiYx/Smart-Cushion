package com.example.smartcushion.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.smartcushion.MainActivity
import com.example.smartcushion.R
import com.example.smartcushion.domain.model.AlarmState

class SmartCushionNotifier(private val context: Context) {
    private val appContext = context.applicationContext

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RUNNING_CHANNEL_ID,
                "Smart Cushion running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that Smart Cushion is running in the background."
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ALARM_CHANNEL_ID,
                "Smart Cushion alarms",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Pressure and repositioning reminders."
            },
        )
    }

    fun buildRunningNotification(): Notification {
        createChannels()
        return NotificationCompat.Builder(appContext, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smart Cushion is running")
            .setContentText("Tap to open the monitoring screen.")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showAlarm(alarm: AlarmState) {
        createChannels()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(appContext, ALARM_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alarm.title)
            .setContentText(alarm.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alarm.message))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Close alarm", dismissPendingIntent(alarm.id))
            .build()

        NotificationManagerCompat.from(appContext).notify(notificationIdFor(alarm.id), notification)
    }

    fun cancelAlarm(alarmId: String) {
        NotificationManagerCompat.from(appContext).cancel(notificationIdFor(alarmId))
    }

    fun cancelRunningNotification() {
        NotificationManagerCompat.from(appContext).cancel(RUNNING_NOTIFICATION_ID)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissPendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(appContext, AlarmActionReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            notificationIdFor(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(alarmId: String): Int =
        ALARM_NOTIFICATION_BASE + alarmId.hashCode().and(0x0FFF_FFFF)

    companion object {
        const val ACTION_DISMISS_ALARM = "com.example.smartcushion.action.DISMISS_ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val RUNNING_NOTIFICATION_ID = 1001

        private const val RUNNING_CHANNEL_ID = "smart_cushion_running"
        private const val ALARM_CHANNEL_ID = "smart_cushion_alarms"
        private const val OPEN_APP_REQUEST_CODE = 2001
        private const val ALARM_NOTIFICATION_BASE = 10_000
    }
}
