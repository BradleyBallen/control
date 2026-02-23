package com.evolcorp.control_parental

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class MyAccessibilityService : AccessibilityService() {
    private val homePackages: Set<String> by lazy { resolveHomePackages() }
    private val browserPackages: Set<String> by lazy { resolveBrowserPackages() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val foregroundPoller = object : Runnable {
        override fun run() {
            runCatching {
                val topPackage = resolveCurrentForegroundPackage(null) ?: return@runCatching
                enforcePolicy(topPackage, null)
            }.onFailure {
                Log.e(TAG, "Periodic foreground check failed", it)
            }
            mainHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MS)
        }
    }

    private var lastBlockedPackage: String? = null
    private var lastBlockTimestampMs: Long = 0L
    private var cachedWebKeywordPackages: Set<String> = emptySet()
    private var cachedWebKeywords: Set<String> = emptySet()
    private var cachedWebKeywordsUpdatedAtMs: Long = 0L
    private var cachedWebKeywordEntriesPackages: Set<String> = emptySet()
    private var cachedWebKeywordEntries: List<WebKeywordEntry> = emptyList()
    private var cachedWebKeywordEntriesUpdatedAtMs: Long = 0L
    private var unknownBrowserContentChecks = 0

    override fun onServiceConnected() {
        runCatching {
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
        }.onFailure {
            Log.e(TAG, "Service connection setup failed", it)
        }
    }

    override fun onDestroy() {
        runCatching { stopForegroundPolling() }
            .onFailure { Log.e(TAG, "Failed to stop polling", it) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (event == null || !isRelevantEvent(event.eventType)) {
                return@runCatching
            }
            val foregroundPackage = resolveCurrentForegroundPackage(event) ?: return@runCatching
            enforcePolicy(foregroundPackage, event)
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

    private fun enforcePolicy(
        foregroundPackage: String,
        event: AccessibilityEvent?
    ) {
        if (shouldIgnorePackage(foregroundPackage)) {
            return
        }
        if (foregroundPackage in browserPackages) {
            val webBlockMatch = resolveWebRouteBlockMatch(event)
            if (webBlockMatch != null) {
                blockForegroundApp(
                    foregroundPackage = foregroundPackage,
                    reason = "${ProtectionPolicyEngine.REASON_WEB_ROUTE_BLOCKED}:${webBlockMatch.keyword}",
                    isSecurityPackage = false,
                    blockedTargetPackage = webBlockMatch.packageName,
                    keepForegroundContext = true
                )
                return
            }
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

    private fun resolveWebRouteBlockMatch(event: AccessibilityEvent?): WebRouteMatch? {
        val blockedPackages = ProtectionPolicyEngine.getWebBlockedPackages(this)
        if (blockedPackages.isEmpty()) {
            cachedWebKeywordPackages = emptySet()
            cachedWebKeywords = emptySet()
            cachedWebKeywordEntriesPackages = emptySet()
            cachedWebKeywordEntries = emptyList()
            unknownBrowserContentChecks = 0
            return null
        }

        if (resolveBlockedWebKeywords(blockedPackages).isEmpty()) {
            return null
        }

        val visibleText = captureVisibleWindowText(event)
        if (visibleText.isBlank()) {
            unknownBrowserContentChecks++
            if (unknownBrowserContentChecks >= WEB_UNKNOWN_CONTENT_THRESHOLD) {
                val fallbackPackage = blockedPackages.firstOrNull() ?: return null
                return WebRouteMatch(
                    packageName = fallbackPackage,
                    keyword = WEB_FALLBACK_KEYWORD
                )
            }
            return null
        }
        unknownBrowserContentChecks = 0

        val keywordEntries = resolveWebKeywordEntries(blockedPackages)
        if (keywordEntries.isEmpty()) {
            return null
        }

        for (entry in keywordEntries) {
            if (visibleText.contains(entry.keyword)) {
                return WebRouteMatch(
                    packageName = entry.packageName,
                    keyword = entry.keyword
                )
            }
        }
        return null
    }

    private fun resolveBlockedWebKeywords(packageNames: Set<String>): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (packageNames == cachedWebKeywordPackages &&
            now - cachedWebKeywordsUpdatedAtMs < WEB_KEYWORD_CACHE_MS
        ) {
            return cachedWebKeywords
        }

        val rebuiltKeywords = buildBlockedWebKeywords(packageNames)
        cachedWebKeywordPackages = packageNames.toSet()
        cachedWebKeywords = rebuiltKeywords
        cachedWebKeywordsUpdatedAtMs = now
        return rebuiltKeywords
    }

    private fun resolveWebKeywordEntries(packageNames: Set<String>): List<WebKeywordEntry> {
        val now = SystemClock.elapsedRealtime()
        if (packageNames == cachedWebKeywordEntriesPackages &&
            now - cachedWebKeywordEntriesUpdatedAtMs < WEB_KEYWORD_CACHE_MS
        ) {
            return cachedWebKeywordEntries
        }

        val rebuiltEntries = buildWebKeywordEntries(packageNames)
        cachedWebKeywordEntriesPackages = packageNames.toSet()
        cachedWebKeywordEntries = rebuiltEntries
        cachedWebKeywordEntriesUpdatedAtMs = now
        return rebuiltEntries
    }

    private fun buildWebKeywordEntries(packageNames: Set<String>): List<WebKeywordEntry> {
        val entries = mutableListOf<WebKeywordEntry>()
        val dedupe = mutableSetOf<String>()

        packageNames.forEach { packageName ->
            val keywordsForPackage = mutableSetOf<String>()
            keywordsForPackage.addAll(resolveKnownWebAliases(packageName))
            keywordsForPackage.addAll(extractKeywords(packageName))
            resolveAppLabel(packageName)?.let { appLabel ->
                keywordsForPackage.addAll(extractKeywords(appLabel))
            }

            keywordsForPackage.forEach { keyword ->
                val normalizedKeyword = keyword.trim().lowercase(Locale.ROOT)
                if (normalizedKeyword.length < 3 && "." !in normalizedKeyword) {
                    return@forEach
                }
                if (normalizedKeyword in WEB_KEYWORD_STOP_WORDS) {
                    return@forEach
                }
                val key = "$packageName|$normalizedKeyword"
                if (dedupe.add(key)) {
                    entries.add(WebKeywordEntry(packageName, normalizedKeyword))
                }
            }
        }

        entries.sortByDescending { it.keyword.length }
        return entries
    }

    private fun buildBlockedWebKeywords(packageNames: Set<String>): Set<String> {
        val keywords = mutableSetOf<String>()
        packageNames.forEach { packageName ->
            keywords.addAll(resolveKnownWebAliases(packageName))
            keywords.addAll(extractKeywords(packageName))
            resolveAppLabel(packageName)?.let { appLabel ->
                keywords.addAll(extractKeywords(appLabel))
            }
        }

        return keywords
            .asSequence()
            .map { keyword -> keyword.trim().lowercase(Locale.ROOT) }
            .filter { keyword -> keyword.length >= 3 || "." in keyword }
            .filterNot { keyword -> keyword in WEB_KEYWORD_STOP_WORDS }
            .toSet()
    }

    private fun resolveKnownWebAliases(packageName: String): Set<String> {
        val normalized = packageName.lowercase(Locale.ROOT)
        val aliases = mutableSetOf<String>()
        KNOWN_WEB_ALIAS_MAP.forEach { (token, values) ->
            if (token in normalized) {
                aliases.addAll(values)
            }
        }
        return aliases
    }

    private fun resolveAppLabel(packageName: String): String? {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return null
        return runCatching {
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }

    private fun extractKeywords(rawValue: String): Set<String> {
        val normalized = rawValue.lowercase(Locale.ROOT)
            .replace(NON_KEYWORD_CHARS_REGEX, " ")
        if (normalized.isBlank()) {
            return emptySet()
        }
        return normalized.split(WHITESPACE_REGEX)
            .asSequence()
            .map { token -> token.trim() }
            .filter { token -> token.isNotBlank() }
            .filterNot { token -> token in WEB_KEYWORD_STOP_WORDS }
            .toSet()
    }

    private fun captureVisibleWindowText(event: AccessibilityEvent?): String {
        val collector = StringBuilder()
        appendAccessibilityEventText(collector, event)

        val root = rootInActiveWindow ?: return collector.toString().lowercase(Locale.ROOT)
        return runCatching {
            val visitedNodes = intArrayOf(0)
            appendNodeText(root, collector, visitedNodes)
            collectBrowserAddressCandidates(root).forEach { candidate ->
                appendTextValue(collector, candidate)
            }
            collector.toString().lowercase(Locale.ROOT)
        }.getOrElse { "" }.also {
            runCatching { root.recycle() }
        }
    }

    private fun appendNodeText(
        node: AccessibilityNodeInfo,
        collector: StringBuilder,
        visitedNodes: IntArray
    ) {
        if (visitedNodes[0] >= MAX_BROWSER_NODE_SCAN) {
            return
        }
        visitedNodes[0]++

        appendTextValue(collector, node.text)
        appendTextValue(collector, node.contentDescription)
        appendTextValue(collector, node.viewIdResourceName)

        val childCount = node.childCount
        for (index in 0 until childCount) {
            if (visitedNodes[0] >= MAX_BROWSER_NODE_SCAN) {
                break
            }
            val child = node.getChild(index) ?: continue
            appendNodeText(child, collector, visitedNodes)
            runCatching { child.recycle() }
        }
    }

    private fun appendTextValue(collector: StringBuilder, value: CharSequence?) {
        if (value.isNullOrBlank()) {
            return
        }
        collector
            .append(' ')
            .append(value.toString().lowercase(Locale.ROOT))
    }

    private fun appendAccessibilityEventText(
        collector: StringBuilder,
        event: AccessibilityEvent?
    ) {
        if (event == null) {
            return
        }
        event.text?.forEach { value -> appendTextValue(collector, value) }
        appendTextValue(collector, event.contentDescription)
    }

    private fun collectBrowserAddressCandidates(root: AccessibilityNodeInfo): Set<String> {
        val candidates = mutableSetOf<String>()
        BROWSER_URL_VIEW_IDS.forEach { viewId ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
                .getOrNull()
                ?: return@forEach
            nodes.forEach { node ->
                appendBrowserNodeCandidate(candidates, node)
                runCatching { node.recycle() }
            }
        }
        return candidates
    }

    private fun appendBrowserNodeCandidate(
        candidates: MutableSet<String>,
        node: AccessibilityNodeInfo
    ) {
        appendCandidateValue(candidates, node.text)
        appendCandidateValue(candidates, node.contentDescription)
        appendCandidateValue(candidates, node.viewIdResourceName)
    }

    private fun appendCandidateValue(
        candidates: MutableSet<String>,
        value: CharSequence?
    ) {
        if (value.isNullOrBlank()) {
            return
        }
        val normalized = value.toString().trim().lowercase(Locale.ROOT)
        if (normalized.length < 3) {
            return
        }
        candidates.add(normalized)
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

    private fun resolveBrowserPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val detected = packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo -> normalizePackageName(resolveInfo.activityInfo?.packageName) }
            .toSet()
        return detected + KNOWN_BROWSER_PACKAGES
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
        isSecurityPackage: Boolean,
        blockedTargetPackage: String? = null,
        keepForegroundContext: Boolean = false
    ) {
        runCatching {
            val now = SystemClock.elapsedRealtime()
            if (foregroundPackage == lastBlockedPackage && now - lastBlockTimestampMs < BLOCK_COOLDOWN_MS) {
                return
            }
            lastBlockedPackage = foregroundPackage
            lastBlockTimestampMs = now
            val eventPackage = blockedTargetPackage ?: foregroundPackage

            ParentalPolicyStore.addEvent(
                context = this,
                type = if (isSecurityPackage) EVENT_SECURITY_ATTEMPT else EVENT_BLOCKED_ATTEMPT,
                packageName = eventPackage,
                reason = reason,
                details = "Bloqueo aplicado desde Accessibility"
            )

            if (!keepForegroundContext) {
                val movedToHome = performGlobalAction(GLOBAL_ACTION_HOME)
                if (!movedToHome) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching { startActivity(fallbackIntent) }
                }
            }
            BlockedAppActivity.launch(this, blockedTargetPackage ?: foregroundPackage, reason)
            runCatching { AppBlockVpnService.sync(this) }
        }.onFailure {
            Log.e(TAG, "Failed to block package: $foregroundPackage", it)
        }
    }

    private data class WebRouteMatch(
        val packageName: String,
        val keyword: String
    )

    private data class WebKeywordEntry(
        val packageName: String,
        val keyword: String
    )

    private companion object {
        private const val TAG = "MyAccessibilityService"
        private const val BLOCK_COOLDOWN_MS = 1100L
        private const val FOREGROUND_POLL_INTERVAL_MS = 500L
        private const val USAGE_LOOKBACK_MS = 15_000L
        private const val MAX_BROWSER_NODE_SCAN = 220
        private const val WEB_KEYWORD_CACHE_MS = 4_000L
        private const val WEB_UNKNOWN_CONTENT_THRESHOLD = 4
        private const val WEB_FALLBACK_KEYWORD = "restricted_web"
        private const val EVENT_BLOCKED_ATTEMPT = "blocked_attempt"
        private const val EVENT_SECURITY_ATTEMPT = "security_attempt"

        private val NON_KEYWORD_CHARS_REGEX = Regex("[^a-z0-9.]")
        private val WHITESPACE_REGEX = Regex("\\s+")

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

        private val WEB_KEYWORD_STOP_WORDS = setOf(
            "com",
            "org",
            "net",
            "app",
            "apps",
            "android",
            "mobile",
            "lite",
            "free",
            "beta",
            "official",
            "the"
        )

        private val KNOWN_WEB_ALIAS_MAP = mapOf(
            "facebook" to setOf("facebook", "facebook.com", "m.facebook.com"),
            "instagram" to setOf("instagram", "instagram.com"),
            "whatsapp" to setOf("whatsapp", "whatsapp.com", "web.whatsapp.com"),
            "telegram" to setOf("telegram", "telegram.org", "web.telegram.org"),
            "snapchat" to setOf("snapchat", "snapchat.com"),
            "tiktok" to setOf("tiktok", "tiktok.com"),
            "musically" to setOf("tiktok", "tiktok.com"),
            "twitter" to setOf("twitter", "twitter.com", "x.com"),
            "reddit" to setOf("reddit", "reddit.com"),
            "pinterest" to setOf("pinterest", "pinterest.com"),
            "linkedin" to setOf("linkedin", "linkedin.com"),
            "discord" to setOf("discord", "discord.com"),
            "bereal" to setOf("bereal", "bere.al", "bereal.com"),
            "messenger" to setOf("messenger", "messenger.com")
        )

        private val BROWSER_URL_VIEW_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.brave.browser:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.vivaldi.browser:id/url_bar"
        )
    }
}
