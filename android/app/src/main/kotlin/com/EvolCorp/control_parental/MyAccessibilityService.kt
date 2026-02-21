package com.EvolCorp.control_parental

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

class MyAccessibilityService : AccessibilityService() {
    private val homePackages: Set<String> by lazy { resolveHomePackages() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val foregroundPoller = object : Runnable {
        override fun run() {
            runCatching {
                val topPackage = resolveCurrentForegroundPackage(null) ?: return@runCatching
                enforcePolicy(topPackage)
            }.onFailure {
                Log.e(TAG, "Periodic foreground check failed", it)
            }
            mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MS)
        }
    }

    private var lastBlockedPackage: String? = null
    private var lastBlockTimestampMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        startForegroundPolling()
        ProtectionForegroundService.sync(this)
    }

    override fun onDestroy() {
        stopForegroundPolling()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (event == null || !isRelevantEvent(event.eventType)) {
                return@runCatching
            }
            val foregroundPackage = resolveCurrentForegroundPackage(event) ?: return@runCatching
            enforcePolicy(foregroundPackage)
        }.onFailure {
            Log.e(TAG, "Accessibility event processing failed", it)
        }
    }

    override fun onInterrupt() = Unit

    private fun startForegroundPolling() {
        mainHandler.removeCallbacks(foregroundPoller)
        mainHandler.post(foregroundPoller)
    }

    private fun stopForegroundPolling() {
        mainHandler.removeCallbacks(foregroundPoller)
    }

    private fun isRelevantEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private fun resolveCurrentForegroundPackage(event: AccessibilityEvent?): String? {
        val fromEvent = normalizePackageName(event?.packageName?.toString())
        if (fromEvent != null) {
            return fromEvent
        }

        val fromWindow = normalizePackageName(rootInActiveWindow?.packageName?.toString())
        if (fromWindow != null) {
            return fromWindow
        }

        return getForegroundPackageFromUsageStats()
    }

    private fun enforcePolicy(foregroundPackage: String) {
        if (shouldIgnorePackage(foregroundPackage)) {
            return
        }
        val decision = ProtectionPolicyEngine.evaluate(this, foregroundPackage)
        if (decision.shouldBlock) {
            blockForegroundApp(
                foregroundPackage = foregroundPackage,
                reason = decision.reason,
                isSecurityPackage = decision.isSecurityPackage
            )
        }
    }

    private fun shouldIgnorePackage(foregroundPackage: String): Boolean {
        if (foregroundPackage in homePackages) {
            return true
        }
        return ProtectionPolicyEngine.shouldIgnorePackage(this, foregroundPackage)
    }

    private fun resolveHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo -> normalizePackageName(resolveInfo.activityInfo?.packageName) }
            .toSet()
    }

    private fun getForegroundPackageFromUsageStats(): String? {
        if (!UsageStatsReporter.hasUsageStatsPermission(this)) {
            return null
        }
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endMs = System.currentTimeMillis()
        val startMs = endMs - USAGE_LOOKBACK_MS
        val events = usageStatsManager.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (!isForegroundUsageEvent(event.eventType)) {
                continue
            }
            val eventPackage = normalizePackageName(event.packageName) ?: continue
            if (event.timeStamp >= latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPackage = eventPackage
            }
        }
        return latestPackage
    }

    private fun isForegroundUsageEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
    }

    private fun normalizePackageName(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        return value.trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
    }

    private fun blockForegroundApp(
        foregroundPackage: String,
        reason: String?,
        isSecurityPackage: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (foregroundPackage == lastBlockedPackage && now - lastBlockTimestampMs < BLOCK_COOLDOWN_MS) {
            return
        }
        lastBlockedPackage = foregroundPackage
        lastBlockTimestampMs = now

        ParentalPolicyStore.addEvent(
            context = this,
            type = if (isSecurityPackage) EVENT_SECURITY_ATTEMPT else EVENT_BLOCKED_ATTEMPT,
            packageName = foregroundPackage,
            reason = reason,
            details = "Bloqueo aplicado desde Accessibility"
        )

        val movedToHome = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!movedToHome) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { startActivity(fallbackIntent) }
        }
        BlockedAppActivity.launch(this, foregroundPackage, reason)
        AppBlockVpnService.sync(this)
    }

    private companion object {
        private const val TAG = "MyAccessibilityService"
        private const val BLOCK_COOLDOWN_MS = 1100L
        private const val FOREGROUND_POLL_INTERVAL_MS = 500L
        private const val USAGE_LOOKBACK_MS = 15_000L
        private const val EVENT_BLOCKED_ATTEMPT = "blocked_attempt"
        private const val EVENT_SECURITY_ATTEMPT = "security_attempt"
    }
}
