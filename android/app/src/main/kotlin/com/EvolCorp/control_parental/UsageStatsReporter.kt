package com.evolcorp.control_parental

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object UsageStatsReporter {
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getTodayUsageMsForPackage(
        context: Context,
        packageName: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val normalizedPackage = normalizePackageName(packageName) ?: return 0L
        if (!hasUsageStatsPermission(context)) {
            return 0L
        }
        val startOfDayMs = startOfDayMs(nowMs)
        val statsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        val stats = statsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDayMs,
            nowMs
        )
        var usageMs = 0L
        stats.orEmpty().forEach { usage ->
            if (normalizePackageName(usage.packageName) == normalizedPackage) {
                usageMs += usage.totalTimeInForeground
            }
        }
        return usageMs
    }

    fun getUsageReport(
        context: Context,
        days: Int,
        packageFilter: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<Map<String, Any>> {
        if (!hasUsageStatsPermission(context)) {
            return emptyList()
        }
        val safeDays = days.coerceIn(1, 30)
        val normalizedFilter = packageFilter.mapNotNull(::normalizePackageName).toSet()
        val statsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val reportRows = mutableListOf<Map<String, Any>>()

        for (offset in 0 until safeDays) {
            val dayStart = startOfDayMs(nowMs) - (offset * DAY_MS)
            val dayEnd = dayStart + DAY_MS
            val stats = statsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                dayStart,
                dayEnd
            ).orEmpty()
            val perPackage = mutableMapOf<String, Long>()
            stats.forEach { usage ->
                val normalizedPackage = normalizePackageName(usage.packageName) ?: return@forEach
                if (normalizedFilter.isNotEmpty() && normalizedPackage !in normalizedFilter) {
                    return@forEach
                }
                perPackage[normalizedPackage] = (perPackage[normalizedPackage] ?: 0L) + usage.totalTimeInForeground
            }
            val dateLabel = formatter.format(Date(dayStart))
            perPackage.entries
                .sortedByDescending { it.value }
                .forEach { (packageName, usageMs) ->
                    reportRows.add(
                        mapOf(
                            "date" to dateLabel,
                            "dayStartMs" to dayStart,
                            "packageName" to packageName,
                            "usageMs" to usageMs,
                            "usageMinutes" to usageMs / 60_000L
                        )
                    )
                }
        }
        return reportRows
    }

    private fun normalizePackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    private fun startOfDayMs(timestampMs: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L
}
