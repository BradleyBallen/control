package com.evolcorp.control_parental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProtectionForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            runCatching {
                checkProtectionSignals()
                AppBlockVpnService.sync(this@ProtectionForegroundService)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    private val signalCooldowns = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        runCatching {
            super.onCreate()
            isRunning = true
        }.onFailure { error ->
            Log.e(TAG, "ProtectionForegroundService onCreate failed", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            if (!ParentalPolicyStore.isProtectionEnabled(this)) {
                stopSelf()
                return@runCatching START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, buildNotification())
            mainHandler.removeCallbacks(poller)
            mainHandler.post(poller)
            START_STICKY
        }.getOrElse { error ->
            Log.e(TAG, "ProtectionForegroundService onStartCommand failed", error)
            mainHandler.removeCallbacks(poller)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        runCatching {
            mainHandler.removeCallbacks(poller)
            isRunning = false
            super.onDestroy()
        }.onFailure { error ->
            Log.e(TAG, "ProtectionForegroundService onDestroy failed", error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkProtectionSignals() {
        if (!isAccessibilityEnabled()) {
            addSignalEvent(
                type = EVENT_ACCESSIBILITY_DISABLED,
                details = "Accessibility service desactivado",
                cooldownMs = 60_000L
            )
        }
        if (!UsageStatsReporter.hasUsageStatsPermission(this)) {
            addSignalEvent(
                type = EVENT_USAGE_ACCESS_MISSING,
                details = "Permiso de uso no otorgado",
                cooldownMs = 60_000L
            )
        }
        if (!isDeviceAdminEnabled()) {
            addSignalEvent(
                type = EVENT_DEVICE_ADMIN_DISABLED,
                details = "Device Admin desactivado",
                cooldownMs = 60_000L
            )
        }
        if (packageManager.isSafeMode) {
            addSignalEvent(
                type = EVENT_SAFE_MODE_DETECTED,
                details = "Safe Mode detectado",
                cooldownMs = 5 * 60_000L
            )
        }
    }

    private fun addSignalEvent(type: String, details: String, cooldownMs: Long) {
        val now = System.currentTimeMillis()
        val lastTimestamp = signalCooldowns[type] ?: 0L
        if (now - lastTimestamp < cooldownMs) {
            return
        }
        signalCooldowns[type] = now
        ParentalPolicyStore.addEvent(
            context = this,
            type = type,
            details = details
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!enabled) {
            return false
        }
        val expectedComponent = ComponentName(this, MyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (componentName == expectedComponent) {
                return true
            }
        }
        return false
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val manager = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val component = ComponentName(this, ParentalDeviceAdminReceiver::class.java)
        return manager.isAdminActive(component)
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Modo enfoque activo")
            .setContentText("Monitoreo de limites y pausas en segundo plano")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Modo enfoque",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones del monitoreo de autocontrol"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        private var isRunning: Boolean = false

        fun isRunning(): Boolean = isRunning

        fun start(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Log.e(TAG, "Failed to start ProtectionForegroundService", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ProtectionForegroundService::class.java))
            }.onFailure { error ->
                Log.e(TAG, "Failed to stop ProtectionForegroundService", error)
            }
            isRunning = false
        }

        fun sync(context: Context) {
            if (!ParentalPolicyStore.isProtectionEnabled(context)) {
                stop(context)
                AppBlockVpnService.stop(context)
                return
            }
            start(context)
            AppBlockVpnService.sync(context)
        }

        private const val POLL_INTERVAL_MS = 20_000L
        private const val NOTIFICATION_CHANNEL_ID = "parental_monitor_channel"
        private const val NOTIFICATION_ID = 4112
        private const val EVENT_ACCESSIBILITY_DISABLED = "accessibility_disabled"
        private const val EVENT_USAGE_ACCESS_MISSING = "usage_access_missing"
        private const val EVENT_DEVICE_ADMIN_DISABLED = "device_admin_disabled"
        private const val EVENT_SAFE_MODE_DETECTED = "safe_mode_detected"
        private const val TAG = "ProtectionService"
    }
}
