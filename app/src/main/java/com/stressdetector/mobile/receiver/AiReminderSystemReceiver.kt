package com.stressdetector.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stressdetector.mobile.di.AppLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reconciles persisted approximate reminders after clock, timezone, or boot changes. */
class AiReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in RECONCILE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppLocator.aiReminderScheduler(context.applicationContext).reconcile()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RECONCILE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}
