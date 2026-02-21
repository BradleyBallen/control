package com.EvolCorp.control_parental

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class ParentalDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        ParentalPolicyStore.addEvent(
            context = context,
            type = EVENT_DEVICE_ADMIN_ENABLED,
            details = "Device Admin habilitado"
        )
        ProtectionForegroundService.sync(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        ParentalPolicyStore.addEvent(
            context = context,
            type = EVENT_DEVICE_ADMIN_DISABLED,
            details = "Device Admin deshabilitado"
        )
        ProtectionForegroundService.sync(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        ParentalPolicyStore.addEvent(
            context = context,
            type = EVENT_DEVICE_ADMIN_DISABLE_REQUESTED,
            details = "Intento de desactivar Device Admin"
        )
        return "Desactivar esta proteccion permite desinstalar o evadir el modo enfoque."
    }

    companion object {
        fun componentName(context: Context): ComponentName {
            return ComponentName(context, ParentalDeviceAdminReceiver::class.java)
        }

        private const val EVENT_DEVICE_ADMIN_ENABLED = "device_admin_enabled"
        private const val EVENT_DEVICE_ADMIN_DISABLED = "device_admin_disabled"
        private const val EVENT_DEVICE_ADMIN_DISABLE_REQUESTED = "device_admin_disable_requested"
    }
}
