package com.evolcorp.control_parental

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            val action = intent?.action.orEmpty()
            if (action !in SUPPORTED_ACTIONS) {
                return
            }
            ParentalPolicyStore.addEvent(
                context = context,
                type = EVENT_PROTECTION_SYNC,
                details = "Sincronizacion en arranque: $action"
            )
            ProtectionForegroundService.sync(context)
        }.onFailure { error ->
            Log.e(TAG, "BootCompletedReceiver failed", error)
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        private const val EVENT_PROTECTION_SYNC = "protection_resynced"
        private const val TAG = "BootCompletedReceiver"
    }
}
