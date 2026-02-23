package com.evolcorp.control_parental

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object ParentalPolicyStore {
    data class AppRule(
        val packageName: String,
        val alwaysBlocked: Boolean = false,
        val dailyLimitMinutes: Int = 0,
        val scheduleEnabled: Boolean = false,
        val scheduleStartMinute: Int = 0,
        val scheduleEndMinute: Int = 1439,
        val allowedDays: Set<Int> = DEFAULT_ALLOWED_DAYS,
        val vpnBlockEnabled: Boolean = true
    )

    data class SecuritySettings(
        val blockSettingsPackages: Boolean = true,
        val protectUninstallFlow: Boolean = true,
        val protectionEnabled: Boolean = true
    )

    data class SecurityEvent(
        val timestampMs: Long,
        val type: String,
        val packageName: String? = null,
        val reason: String? = null,
        val details: String? = null
    )

    private data class State(
        val alwaysBlocked: MutableSet<String> = mutableSetOf(),
        val rules: MutableMap<String, AppRule> = mutableMapOf(),
        var pinHash: String? = null,
        val temporaryUnlocks: MutableMap<String, Long> = mutableMapOf(),
        var settings: SecuritySettings = SecuritySettings(),
        val events: MutableList<SecurityEvent> = mutableListOf()
    )

    private const val STORAGE_FILE_NAME = "parental_policy_store.json"
    private const val LEGACY_BLOCKED_FILE_NAME = "blocked_apps_store.txt"
    private const val MAX_EVENTS = 600
    private const val MIN_PIN_LENGTH = 4
    private const val TEMP_UNLOCK_MIN_MS = 30_000L
    private const val TEMP_UNLOCK_MAX_MS = 4 * 60 * 60 * 1000L
    private val lock = Any()
    private val DEFAULT_ALLOWED_DAYS = (1..7).toSet()

    fun getAlwaysBlocked(context: Context): Set<String> {
        synchronized(lock) {
            return readState(context).alwaysBlocked.toSet()
        }
    }

    fun block(context: Context, packageName: String) {
        val normalized = normalizePackageName(packageName) ?: return
        synchronized(lock) {
            val state = readState(context)
            if (state.alwaysBlocked.add(normalized)) {
                writeState(context, state)
            }
        }
    }

    fun unblock(context: Context, packageName: String) {
        val normalized = normalizePackageName(packageName) ?: return
        synchronized(lock) {
            val state = readState(context)
            var changed = false
            if (state.alwaysBlocked.remove(normalized)) {
                changed = true
            }
            if (state.temporaryUnlocks.remove(normalized) != null) {
                changed = true
            }
            if (changed) {
                writeState(context, state)
            }
        }
    }

    fun setAlwaysBlocked(context: Context, packageNames: Collection<String>) {
        synchronized(lock) {
            val state = readState(context)
            val normalized = packageNames.mapNotNull(::normalizePackageName).toSet()
            state.alwaysBlocked.clear()
            state.alwaysBlocked.addAll(normalized)
            state.temporaryUnlocks.keys.removeAll { it !in normalized && it !in state.rules.keys }
            writeState(context, state)
        }
    }

    fun isAlwaysBlocked(context: Context, packageName: String): Boolean {
        val normalized = normalizePackageName(packageName) ?: return false
        synchronized(lock) {
            return readState(context).alwaysBlocked.contains(normalized)
        }
    }

    fun getRules(context: Context): Map<String, AppRule> {
        synchronized(lock) {
            return readState(context).rules.toMap()
        }
    }

    fun getRule(context: Context, packageName: String): AppRule? {
        val normalized = normalizePackageName(packageName) ?: return null
        synchronized(lock) {
            return readState(context).rules[normalized]
        }
    }

    fun upsertRule(context: Context, rule: AppRule) {
        val normalized = normalizePackageName(rule.packageName) ?: return
        synchronized(lock) {
            val state = readState(context)
            state.rules[normalized] = rule.copy(
                packageName = normalized,
                dailyLimitMinutes = rule.dailyLimitMinutes.coerceIn(0, 24 * 60),
                scheduleStartMinute = rule.scheduleStartMinute.coerceIn(0, 1439),
                scheduleEndMinute = rule.scheduleEndMinute.coerceIn(0, 1439),
                allowedDays = normalizeAllowedDays(rule.allowedDays)
            )
            writeState(context, state)
        }
    }

    fun removeRule(context: Context, packageName: String) {
        val normalized = normalizePackageName(packageName) ?: return
        synchronized(lock) {
            val state = readState(context)
            var changed = false
            if (state.rules.remove(normalized) != null) {
                changed = true
            }
            if (state.temporaryUnlocks.remove(normalized) != null) {
                changed = true
            }
            if (changed) {
                writeState(context, state)
            }
        }
    }

    fun hasPin(context: Context): Boolean {
        synchronized(lock) {
            return !readState(context).pinHash.isNullOrBlank()
        }
    }

    fun setPin(context: Context, pin: String): Boolean {
        val normalizedPin = normalizePin(pin) ?: return false
        synchronized(lock) {
            val state = readState(context)
            state.pinHash = hashPin(normalizedPin)
            writeState(context, state)
            return true
        }
    }

    fun clearPin(context: Context) {
        synchronized(lock) {
            val state = readState(context)
            if (state.pinHash != null) {
                state.pinHash = null
                writeState(context, state)
            }
        }
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val normalizedPin = normalizePin(pin) ?: return false
        synchronized(lock) {
            val pinHash = readState(context).pinHash ?: return false
            return pinHash == hashPin(normalizedPin)
        }
    }

    fun grantTemporaryUnlock(context: Context, packageName: String, durationMs: Long) {
        val normalized = normalizePackageName(packageName) ?: return
        val safeDuration = durationMs.coerceIn(TEMP_UNLOCK_MIN_MS, TEMP_UNLOCK_MAX_MS)
        synchronized(lock) {
            val state = readState(context)
            state.temporaryUnlocks[normalized] = System.currentTimeMillis() + safeDuration
            writeState(context, state)
        }
    }

    fun revokeTemporaryUnlock(context: Context, packageName: String) {
        val normalized = normalizePackageName(packageName) ?: return
        synchronized(lock) {
            val state = readState(context)
            if (state.temporaryUnlocks.remove(normalized) != null) {
                writeState(context, state)
            }
        }
    }

    fun isTemporarilyUnlocked(context: Context, packageName: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = normalizePackageName(packageName) ?: return false
        synchronized(lock) {
            val state = readState(context)
            cleanupExpiredUnlocks(state, nowMs)
            val expiration = state.temporaryUnlocks[normalized] ?: return false
            val active = expiration > nowMs
            if (!active) {
                state.temporaryUnlocks.remove(normalized)
                writeState(context, state)
            }
            return active
        }
    }

    fun getSettings(context: Context): SecuritySettings {
        synchronized(lock) {
            return readState(context).settings
        }
    }

    fun updateSettings(context: Context, settings: SecuritySettings) {
        synchronized(lock) {
            val state = readState(context)
            state.settings = settings
            writeState(context, state)
        }
    }

    fun isProtectionEnabled(context: Context): Boolean {
        synchronized(lock) {
            return readState(context).settings.protectionEnabled
        }
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            val state = readState(context)
            if (state.settings.protectionEnabled == enabled) {
                return
            }
            state.settings = state.settings.copy(protectionEnabled = enabled)
            writeState(context, state)
        }
    }

    fun addEvent(
        context: Context,
        type: String,
        packageName: String? = null,
        reason: String? = null,
        details: String? = null
    ) {
        synchronized(lock) {
            val normalizedType = type.trim().lowercase(Locale.ROOT)
            if (normalizedType.isBlank()) {
                return
            }
            val state = readState(context)
            state.events.add(
                SecurityEvent(
                    timestampMs = System.currentTimeMillis(),
                    type = normalizedType,
                    packageName = normalizePackageName(packageName),
                    reason = reason?.trim(),
                    details = details?.trim()
                )
            )
            trimEvents(state)
            writeState(context, state)
        }
    }

    fun getEvents(context: Context, limit: Int = 120): List<SecurityEvent> {
        synchronized(lock) {
            val maxItems = limit.coerceIn(1, MAX_EVENTS)
            return readState(context).events
                .asSequence()
                .sortedByDescending { it.timestampMs }
                .take(maxItems)
                .toList()
        }
    }

    fun clearEvents(context: Context) {
        synchronized(lock) {
            val state = readState(context)
            if (state.events.isEmpty()) {
                return
            }
            state.events.clear()
            writeState(context, state)
        }
    }

    fun toMap(rule: AppRule): Map<String, Any> {
        return mapOf(
            "packageName" to rule.packageName,
            "alwaysBlocked" to rule.alwaysBlocked,
            "dailyLimitMinutes" to rule.dailyLimitMinutes,
            "scheduleEnabled" to rule.scheduleEnabled,
            "scheduleStartMinute" to rule.scheduleStartMinute,
            "scheduleEndMinute" to rule.scheduleEndMinute,
            "allowedDays" to rule.allowedDays.sorted(),
            "vpnBlockEnabled" to rule.vpnBlockEnabled
        )
    }

    fun fromMap(raw: Map<*, *>): AppRule? {
        val packageName = normalizePackageName(raw["packageName"] as? String) ?: return null
        val alwaysBlocked = raw["alwaysBlocked"] as? Boolean ?: false
        val dailyLimitMinutes = (raw["dailyLimitMinutes"] as? Number)?.toInt() ?: 0
        val scheduleEnabled = raw["scheduleEnabled"] as? Boolean ?: false
        val scheduleStartMinute = (raw["scheduleStartMinute"] as? Number)?.toInt() ?: 0
        val scheduleEndMinute = (raw["scheduleEndMinute"] as? Number)?.toInt() ?: 1439
        val allowedDays = (raw["allowedDays"] as? List<*>)?.mapNotNull { day ->
            (day as? Number)?.toInt()?.takeIf { it in 1..7 }
        }?.toSet() ?: DEFAULT_ALLOWED_DAYS
        val vpnBlockEnabled = raw["vpnBlockEnabled"] as? Boolean ?: true
        return AppRule(
            packageName = packageName,
            alwaysBlocked = alwaysBlocked,
            dailyLimitMinutes = dailyLimitMinutes.coerceIn(0, 24 * 60),
            scheduleEnabled = scheduleEnabled,
            scheduleStartMinute = scheduleStartMinute.coerceIn(0, 1439),
            scheduleEndMinute = scheduleEndMinute.coerceIn(0, 1439),
            allowedDays = normalizeAllowedDays(allowedDays),
            vpnBlockEnabled = vpnBlockEnabled
        )
    }

    fun settingsToMap(settings: SecuritySettings): Map<String, Any> {
        return mapOf(
            "blockSettingsPackages" to settings.blockSettingsPackages,
            "protectUninstallFlow" to settings.protectUninstallFlow,
            "protectionEnabled" to settings.protectionEnabled
        )
    }

    fun settingsFromMap(raw: Map<*, *>): SecuritySettings {
        val current = SecuritySettings()
        return SecuritySettings(
            blockSettingsPackages = raw["blockSettingsPackages"] as? Boolean ?: current.blockSettingsPackages,
            protectUninstallFlow = raw["protectUninstallFlow"] as? Boolean ?: current.protectUninstallFlow,
            protectionEnabled = raw["protectionEnabled"] as? Boolean ?: current.protectionEnabled
        )
    }

    fun eventToMap(event: SecurityEvent): Map<String, Any?> {
        return mapOf(
            "timestampMs" to event.timestampMs,
            "type" to event.type,
            "packageName" to event.packageName,
            "reason" to event.reason,
            "details" to event.details
        )
    }

    private fun readState(context: Context): State {
        val storage = storage(context)
        if (!storage.baseFile.exists()) {
            return migrateLegacyState(context).also {
                writeState(context, it)
            }
        }
        val jsonText = try {
            storage.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        } catch (_: IOException) {
            ""
        }
        if (jsonText.isBlank()) {
            return migrateLegacyState(context)
        }
        return parseState(jsonText).also { parsed ->
            cleanupExpiredUnlocks(parsed, System.currentTimeMillis())
            trimEvents(parsed)
        }
    }

    private fun writeState(context: Context, state: State) {
        val storage = storage(context)
        cleanupExpiredUnlocks(state, System.currentTimeMillis())
        trimEvents(state)
        val payload = serializeState(state)
        var output: FileOutputStream? = null
        try {
            output = storage.startWrite()
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            storage.finishWrite(output)
        } catch (_: IOException) {
            if (output != null) {
                storage.failWrite(output)
            }
        }
    }

    private fun serializeState(state: State): String {
        val root = JSONObject()
        root.put("alwaysBlocked", JSONArray().apply {
            state.alwaysBlocked.sorted().forEach(::put)
        })
        root.put("rules", JSONArray().apply {
            state.rules.values.sortedBy { it.packageName }.forEach { rule ->
                put(ruleToJson(rule))
            }
        })
        root.put("pinHash", state.pinHash ?: JSONObject.NULL)
        root.put("temporaryUnlocks", JSONObject().apply {
            state.temporaryUnlocks.forEach { (pkg, expiry) ->
                put(pkg, expiry)
            }
        })
        root.put("settings", settingsToJson(state.settings))
        root.put("events", JSONArray().apply {
            state.events.sortedBy { it.timestampMs }.forEach { event ->
                put(eventToJson(event))
            }
        })
        return root.toString()
    }

    private fun parseState(raw: String): State {
        return try {
            val root = JSONObject(raw)
            val alwaysBlocked = mutableSetOf<String>().apply {
                val array = root.optJSONArray("alwaysBlocked") ?: JSONArray()
                for (index in 0 until array.length()) {
                    normalizePackageName(array.optString(index, null))?.let(::add)
                }
            }
            val rules = mutableMapOf<String, AppRule>().apply {
                val array = root.optJSONArray("rules") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val rule = jsonToRule(item) ?: continue
                    put(rule.packageName, rule)
                }
            }
            val pinHash = root.optString("pinHash", null)?.takeIf { it.isNotBlank() }
            val temporaryUnlocks = mutableMapOf<String, Long>().apply {
                val obj = root.optJSONObject("temporaryUnlocks") ?: JSONObject()
                obj.keys().forEach { rawKey ->
                    val key = normalizePackageName(rawKey) ?: return@forEach
                    val expiry = obj.optLong(rawKey, 0L)
                    if (expiry > 0L) {
                        put(key, expiry)
                    }
                }
            }
            val settings = jsonToSettings(root.optJSONObject("settings") ?: JSONObject())
            val events = mutableListOf<SecurityEvent>().apply {
                val array = root.optJSONArray("events") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val event = jsonToEvent(item) ?: continue
                    add(event)
                }
            }
            State(
                alwaysBlocked = alwaysBlocked,
                rules = rules,
                pinHash = pinHash,
                temporaryUnlocks = temporaryUnlocks,
                settings = settings,
                events = events
            )
        } catch (_: Exception) {
            State()
        }
    }

    private fun migrateLegacyState(context: Context): State {
        val state = State()
        val legacyFile = File(context.applicationContext.filesDir, LEGACY_BLOCKED_FILE_NAME)
        if (legacyFile.exists()) {
            runCatching {
                legacyFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.lineSequence().mapNotNull(::normalizePackageName).forEach(state.alwaysBlocked::add)
                }
            }
        }
        return state
    }

    private fun storage(context: Context): AtomicFile {
        return AtomicFile(File(context.applicationContext.filesDir, STORAGE_FILE_NAME))
    }

    private fun ruleToJson(rule: AppRule): JSONObject {
        return JSONObject().apply {
            put("packageName", rule.packageName)
            put("alwaysBlocked", rule.alwaysBlocked)
            put("dailyLimitMinutes", rule.dailyLimitMinutes)
            put("scheduleEnabled", rule.scheduleEnabled)
            put("scheduleStartMinute", rule.scheduleStartMinute)
            put("scheduleEndMinute", rule.scheduleEndMinute)
            put("allowedDays", JSONArray().apply {
                normalizeAllowedDays(rule.allowedDays).sorted().forEach(::put)
            })
            put("vpnBlockEnabled", rule.vpnBlockEnabled)
        }
    }

    private fun jsonToRule(json: JSONObject): AppRule? {
        val packageName = normalizePackageName(json.optString("packageName", null)) ?: return null
        val allowedDaysArray = json.optJSONArray("allowedDays") ?: JSONArray()
        val allowedDays = mutableSetOf<Int>()
        for (index in 0 until allowedDaysArray.length()) {
            val value = allowedDaysArray.optInt(index, -1)
            if (value in 1..7) {
                allowedDays.add(value)
            }
        }
        return AppRule(
            packageName = packageName,
            alwaysBlocked = json.optBoolean("alwaysBlocked", false),
            dailyLimitMinutes = json.optInt("dailyLimitMinutes", 0).coerceIn(0, 24 * 60),
            scheduleEnabled = json.optBoolean("scheduleEnabled", false),
            scheduleStartMinute = json.optInt("scheduleStartMinute", 0).coerceIn(0, 1439),
            scheduleEndMinute = json.optInt("scheduleEndMinute", 1439).coerceIn(0, 1439),
            allowedDays = normalizeAllowedDays(allowedDays),
            vpnBlockEnabled = json.optBoolean("vpnBlockEnabled", true)
        )
    }

    private fun settingsToJson(settings: SecuritySettings): JSONObject {
        return JSONObject().apply {
            put("blockSettingsPackages", settings.blockSettingsPackages)
            put("protectUninstallFlow", settings.protectUninstallFlow)
            put("protectionEnabled", settings.protectionEnabled)
        }
    }

    private fun jsonToSettings(json: JSONObject): SecuritySettings {
        return SecuritySettings(
            blockSettingsPackages = json.optBoolean("blockSettingsPackages", true),
            protectUninstallFlow = json.optBoolean("protectUninstallFlow", true),
            protectionEnabled = json.optBoolean("protectionEnabled", true)
        )
    }

    private fun eventToJson(event: SecurityEvent): JSONObject {
        return JSONObject().apply {
            put("timestampMs", event.timestampMs)
            put("type", event.type)
            put("packageName", event.packageName ?: JSONObject.NULL)
            put("reason", event.reason ?: JSONObject.NULL)
            put("details", event.details ?: JSONObject.NULL)
        }
    }

    private fun jsonToEvent(json: JSONObject): SecurityEvent? {
        val timestampMs = json.optLong("timestampMs", 0L)
        val type = json.optString("type", "").trim().lowercase(Locale.ROOT)
        if (timestampMs <= 0L || type.isBlank()) {
            return null
        }
        return SecurityEvent(
            timestampMs = timestampMs,
            type = type,
            packageName = normalizePackageName(json.optString("packageName", null)),
            reason = json.optString("reason", null)?.takeIf { it.isNotBlank() },
            details = json.optString("details", null)?.takeIf { it.isNotBlank() }
        )
    }

    private fun normalizePackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
    }

    private fun normalizeAllowedDays(days: Set<Int>): Set<Int> {
        val normalized = days.filter { it in 1..7 }.toSet()
        return if (normalized.isEmpty()) DEFAULT_ALLOWED_DAYS else normalized
    }

    private fun normalizePin(pin: String?): String? {
        if (pin.isNullOrBlank()) {
            return null
        }
        val normalized = pin.trim()
        if (normalized.length < MIN_PIN_LENGTH || normalized.length > 10) {
            return null
        }
        if (!normalized.all { it.isDigit() }) {
            return null
        }
        return normalized
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("control-parental-local-salt".toByteArray(StandardCharsets.UTF_8))
        digest.update(pin.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun cleanupExpiredUnlocks(state: State, nowMs: Long) {
        val before = state.temporaryUnlocks.size
        state.temporaryUnlocks.entries.removeIf { (_, expiry) -> expiry <= nowMs }
        if (before != state.temporaryUnlocks.size) {
            trimEvents(state)
        }
    }

    private fun trimEvents(state: State) {
        if (state.events.size <= MAX_EVENTS) {
            return
        }
        val keep = state.events.sortedByDescending { it.timestampMs }.take(MAX_EVENTS).sortedBy { it.timestampMs }
        state.events.clear()
        state.events.addAll(keep)
    }

    fun defaultTemporaryUnlockMs(): Long {
        return TimeUnit.MINUTES.toMillis(5)
    }
}
