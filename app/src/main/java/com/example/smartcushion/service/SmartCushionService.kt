package com.example.smartcushion.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.smartcushion.controller.SmartCushionControllerProvider
import com.example.smartcushion.notification.SmartCushionNotifier

class SmartCushionService : Service() {
    private lateinit var notifier: SmartCushionNotifier

    override fun onCreate() {
        super.onCreate()
        notifier = SmartCushionNotifier(this)
        startForeground(
            SmartCushionNotifier.RUNNING_NOTIFICATION_ID,
            notifier.buildRunningNotification(),
        )
        SmartCushionControllerProvider.get(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SmartCushionControllerProvider.get(this).start()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSmartCushion()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSmartCushion()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSmartCushion() {
        SmartCushionControllerProvider.get(this).stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifier.cancelRunningNotification()
    }
}
