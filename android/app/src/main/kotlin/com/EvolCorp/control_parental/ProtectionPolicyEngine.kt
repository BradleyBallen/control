package com.evolcorp.control_parental

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Calendar
import java.util.Locale

object ProtectionPolicyEngine {
    data class BlockDecision(
        val shouldBlock: Boolean,
        val reason: String? = null,
        val isSecurityPackage: Boolean = false
    )

    fun evaluate(
        context: Context,
        packageName: String,
        nowMs: Long = System.currentTimeMillis()
    ): BlockDecision {
        val normalizedPackage = normalizePackageName(packageName) ?: return allow()
        val settings = ParentalPolicyStore.getSettings(context)
        if (ParentalPolicyStore.isTemporarilyUnlocked(context, normalizedPackage, nowMs)) {
            return allow()
        }

        if (settings.blockSettingsPackages && normalizedPackage in SETTINGS_PACKAGES) {
            return blocked(REASON_SETTINGS_PROTECTED, isSecurityPackage = true)
        }
        if (settings.protectUninstallFlow && normalizedPackage in PACKAGE_INSTALLER_PACKAGES) {
            return blocked(REASON_UNINSTALL_PROTECTED, isSecurityPackage = true)
        }

        val alwaysBlocked = ParentalPolicyStore.getAlwaysBlocked(context)
        if (normalizedPackage in alwaysBlocked) {
            return blocked(REASON_ALWAYS_BLOCKED)
        }

        val rule = ParentalPolicyStore.getRule(context, normalizedPackage) ?: return allow()
        if (rule.alwaysBlocked && !rule.vpnBlockEnabled) {
            return blocked(REASON_ALWAYS_BLOCKED)
        }
        if (rule.scheduleEnabled && !isWithinAllowedSchedule(rule, nowMs)) {
            return blocked(REASON_OUTSIDE_SCHEDULE)
        }
        if (rule.dailyLimitMinutes > 0) {
            val usedTodayMs = UsageStatsReporter.getTodayUsageMsForPackage(context, normalizedPackage, nowMs)
            if (usedTodayMs >= rule.dailyLimitMinutes * ONE_MINUTE_MS) {
                return blocked(REASON_DAILY_LIMIT_REACHED)
            }
        }
        return allow()
    }

    fun getTrackedPackages(context: Context): Set<String> {
        val tracked = mutableSetOf<String>()
        tracked.addAll(ParentalPolicyStore.getAlwaysBlocked(context))
        tracked.addAll(ParentalPolicyStore.getRules(context).keys)
        val settings = ParentalPolicyStore.getSettings(context)
        if (settings.blockSettingsPackages) {
            tracked.addAll(SETTINGS_PACKAGES)
        }
        if (settings.protectUninstallFlow) {
            tracked.addAll(PACKAGE_INSTALLER_PACKAGES)
        }
        return tracked
    }

    fun getVpnBlockedPackages(context: Context, nowMs: Long = System.currentTimeMillis()): Set<String> {
        val blocked = mutableSetOf<String>()
        val rules = ParentalPolicyStore.getRules(context)
        val alwaysBlocked = ParentalPolicyStore.getAlwaysBlocked(context)

        rules.forEach { (packageName, rule) ->
            if (!rule.vpnBlockEnabled) {
                return@forEach
            }
            if (ParentalPolicyStore.isTemporarilyUnlocked(context, packageName, nowMs)) {
                return@forEach
            }
            if (rule.alwaysBlocked) {
                blocked.add(packageName)
                return@forEach
            }
            if (rule.scheduleEnabled && !isWithinAllowedSchedule(rule, nowMs)) {
                blocked.add(packageName)
                return@forEach
            }
            if (rule.dailyLimitMinutes > 0) {
                val usedTodayMs = UsageStatsReporter.getTodayUsageMsForPackage(context, packageName, nowMs)
                if (usedTodayMs >= rule.dailyLimitMinutes * ONE_MINUTE_MS) {
                    blocked.add(packageName)
                }
            }
        }

        alwaysBlocked.forEach { packageName ->
            if (!ParentalPolicyStore.isTemporarilyUnlocked(context, packageName, nowMs)) {
                blocked.add(packageName)
            }
        }

        // Hardening against DNS-over-HTTPS bypasses: if web blocking is active,
        // also route browser apps through the blocking VPN path.
        if (getWebBlockedPackages(context, nowMs).isNotEmpty()) {
            blocked.addAll(resolveBrowserPackages(context))
        }
        return blocked
    }

    fun getWebBlockedPackages(context: Context, nowMs: Long = System.currentTimeMillis()): Set<String> {
        val blocked = mutableSetOf<String>()
        val rules = ParentalPolicyStore.getRules(context)
        rules.forEach { (packageName, rule) ->
            if (!rule.alwaysBlocked || !rule.vpnBlockEnabled) {
                return@forEach
            }
            if (ParentalPolicyStore.isTemporarilyUnlocked(context, packageName, nowMs)) {
                return@forEach
            }
            blocked.add(packageName)
        }
        return blocked
    }

    fun shouldIgnorePackage(context: Context, packageName: String): Boolean {
        val normalizedPackage = normalizePackageName(packageName) ?: return true
        val selfPackage = normalizePackageName(context.applicationContext.packageName)
        if (normalizedPackage == selfPackage) {
            return true
        }
        return normalizedPackage in ALWAYS_ALLOWED_PACKAGES
    }

    private fun isWithinAllowedSchedule(rule: ParentalPolicyStore.AppRule, nowMs: Long): Boolean {
        if (!rule.scheduleEnabled) {
            return true
        }
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
        val isoDay = toIsoDay(calendar.get(Calendar.DAY_OF_WEEK))
        if (isoDay !in rule.allowedDays) {
            return false
        }
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val start = rule.scheduleStartMinute.coerceIn(0, 1439)
        val end = rule.scheduleEndMinute.coerceIn(0, 1439)
        if (start == end) {
            return true
        }
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    private fun toIsoDay(calendarDayOfWeek: Int): Int {
        return when (calendarDayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    private fun normalizePackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    private fun resolveBrowserPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val detected = context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo -> normalizePackageName(resolveInfo.activityInfo?.packageName) }
            .toSet()
        return detected + KNOWN_BROWSER_PACKAGES
    }

    private fun allow(): BlockDecision {
        return BlockDecision(shouldBlock = false)
    }

    private fun blocked(reason: String, isSecurityPackage: Boolean = false): BlockDecision {
        return BlockDecision(
            shouldBlock = true,
            reason = reason,
            isSecurityPackage = isSecurityPackage
        )
    }

    const val REASON_ALWAYS_BLOCKED = "always_blocked"
    const val REASON_OUTSIDE_SCHEDULE = "outside_schedule"
    const val REASON_DAILY_LIMIT_REACHED = "daily_limit_reached"
    const val REASON_WEB_ROUTE_BLOCKED = "web_route_blocked"
    const val REASON_SETTINGS_PROTECTED = "settings_protected"
    const val REASON_UNINSTALL_PROTECTED = "uninstall_flow_protected"

    private val ALWAYS_ALLOWED_PACKAGES = setOf(
        "android",
        "com.android.systemui"
    )
    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.samsung.android.settings"
    )
    private val PACKAGE_INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )
    private val KNOWN_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.mi.globalbrowser"
    )
    private const val ONE_MINUTE_MS = 60_000L
}
