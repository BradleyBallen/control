package com.EvolCorp.control_parental

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
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
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        private const val EVENT_PROTECTION_SYNC = "protection_resynced"
    }
}
