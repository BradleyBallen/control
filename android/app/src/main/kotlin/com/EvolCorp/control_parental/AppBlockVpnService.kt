package com.evolcorp.control_parental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AppBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            if (!ParentalPolicyStore.isProtectionEnabled(this)) {
                stopVpnTunnel()
                stopSelf()
                return@runCatching START_NOT_STICKY
            }

            val blockedApps = ProtectionPolicyEngine.getVpnBlockedPackages(this)
            if (blockedApps.isEmpty()) {
                stopVpnTunnel()
                stopSelf()
                return@runCatching START_NOT_STICKY
            }

            val vpnPrepared = VpnService.prepare(this) == null
            if (!vpnPrepared) {
                stopVpnTunnel()
                stopSelf()
                return@runCatching START_NOT_STICKY
            }

            startForeground(NOTIFICATION_ID, buildNotification())
            establishVpnTunnel(blockedApps)
            isActive = vpnInterface != null
            START_STICKY
        }.getOrElse { error ->
            Log.e(TAG, "AppBlockVpnService onStartCommand failed", error)
            stopVpnTunnel()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        runCatching { stopVpnTunnel() }
            .onFailure { error -> Log.e(TAG, "AppBlockVpnService onDestroy failed", error) }
        super.onDestroy()
    }

    override fun onRevoke() {
        runCatching {
            stopVpnTunnel()
            stopSelf()
        }.onFailure { error ->
            Log.e(TAG, "AppBlockVpnService onRevoke failed", error)
        }
        super.onRevoke()
    }

    private fun establishVpnTunnel(blockedApps: Set<String>) {
        stopVpnTunnel()

        val builder = Builder()
            .setSession(VPN_SESSION_NAME)
            .setMtu(1500)
            .addAddress("10.222.0.1", 32)
            .addRoute("0.0.0.0", 0)

        runCatching { builder.addRoute("::", 0) }

        blockedApps.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
        }

        vpnInterface = runCatching { builder.establish() }.getOrNull()
        val descriptor = vpnInterface?.fileDescriptor ?: return

        running.set(true)
        val inputStream = FileInputStream(descriptor)
        drainThread = Thread {
            val buffer = ByteArray(32767)
            while (running.get()) {
                try {
                    if (inputStream.read(buffer) < 0) {
                        break
                    }
                } catch (_: IOException) {
                    break
                }
            }
            runCatching { inputStream.close() }
        }.apply {
            name = "AppBlockVpnDrain"
            isDaemon = true
            start()
        }
    }

    private fun stopVpnTunnel() {
        running.set(false)
        drainThread?.interrupt()
        drainThread = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bloqueo de apps activo")
            .setContentText("VPN local aplicando pausas y limites")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Bloqueo VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene activo el bloqueo de internet por app"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        private var isActive: Boolean = false

        fun hasPermission(context: Context): Boolean {
            return VpnService.prepare(context) == null
        }

        fun isRunning(): Boolean {
            return isActive
        }

        fun sync(context: Context) {
            if (!ParentalPolicyStore.isProtectionEnabled(context)) {
                stop(context)
                return
            }
            if (ProtectionPolicyEngine.getVpnBlockedPackages(context).isEmpty()) {
                stop(context)
                return
            }
            if (!hasPermission(context)) {
                stop(context)
                return
            }
            start(context)
        }

        fun start(context: Context) {
            if (!hasPermission(context)) {
                return
            }
            val intent = Intent(context, AppBlockVpnService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Log.e(TAG, "Failed to start AppBlockVpnService", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AppBlockVpnService::class.java))
            }.onFailure { error ->
                Log.e(TAG, "Failed to stop AppBlockVpnService", error)
            }
            isActive = false
        }

        private const val VPN_SESSION_NAME = "ControlDeUsoVpnBlock"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_blocking_channel"
        private const val NOTIFICATION_ID = 1042
        private const val TAG = "AppBlockVpnService"
    }
}
