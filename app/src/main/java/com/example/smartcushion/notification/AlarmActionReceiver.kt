package com.example.smartcushion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smartcushion.controller.SmartCushionControllerProvider

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmartCushionNotifier.ACTION_DISMISS_ALARM) return

        val alarmId = intent.getStringExtra(SmartCushionNotifier.EXTRA_ALARM_ID) ?: return
        SmartCushionControllerProvider.get(context).dismissAlarm(alarmId)
    }
}
