package com.jaky.notifylink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ListenerKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot_completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            else -> "keep_alive_broadcast"
        }
        NotificationService.ensureListenerRunning(context, reason)
    }
}
