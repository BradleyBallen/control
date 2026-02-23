package com.evolcorp.control_parental

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingVpnPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME
        ).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    METHOD_BLOCK,
                    METHOD_BLOCK_APP -> {
                        val packageName = extractPackageName(call)
                        if (packageName.isNullOrBlank()) {
                            result.error("INVALID_ARGUMENT", "packageName is required", null)
                            return@setMethodCallHandler
                        }
                        BlockedAppsManager.block(this, packageName)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_UNBLOCK,
                    METHOD_UNBLOCK_APP -> {
                        val packageName = extractPackageName(call)
                        if (packageName.isNullOrBlank()) {
                            result.error("INVALID_ARGUMENT", "packageName is required", null)
                            return@setMethodCallHandler
                        }
                        BlockedAppsManager.unblock(this, packageName)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_IS_BLOCKED -> {
                        val packageName = extractPackageName(call)
                        if (packageName.isNullOrBlank()) {
                            result.error("INVALID_ARGUMENT", "packageName is required", null)
                            return@setMethodCallHandler
                        }
                        result.success(BlockedAppsManager.isBlocked(this, packageName))
                    }

                    METHOD_GET_BLOCKED_APPS -> {
                        result.success(BlockedAppsManager.getBlockedApps(this).toList())
                    }

                    METHOD_SET_BLOCKED_APPS -> {
                        val packageNames = call.argument<List<String>>(ARG_PACKAGE_NAMES) ?: emptyList()
                        BlockedAppsManager.setBlockedApps(this, packageNames)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_GET_APP_RULES -> {
                        val rules = ParentalPolicyStore.getRules(this)
                            .values
                            .sortedBy { it.packageName }
                            .map(ParentalPolicyStore::toMap)
                        result.success(rules)
                    }

                    METHOD_UPSERT_APP_RULE -> {
                        val rawRule = call.argument<Map<String, Any?>>(ARG_RULE)
                        if (rawRule == null) {
                            result.error("INVALID_ARGUMENT", "rule is required", null)
                            return@setMethodCallHandler
                        }
                        val parsedRule = ParentalPolicyStore.fromMap(rawRule)
                        if (parsedRule == null) {
                            result.error("INVALID_ARGUMENT", "rule is invalid", null)
                            return@setMethodCallHandler
                        }
                        ParentalPolicyStore.upsertRule(this, parsedRule)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_REMOVE_APP_RULE -> {
                        val packageName = extractPackageName(call)
                        if (packageName.isNullOrBlank()) {
                            result.error("INVALID_ARGUMENT", "packageName is required", null)
                            return@setMethodCallHandler
                        }
                        ParentalPolicyStore.removeRule(this, packageName)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_IS_ACCESSIBILITY_ENABLED -> {
                        result.success(isAccessibilityServiceEnabled())
                    }

                    METHOD_OPEN_ACCESSIBILITY_SETTINGS -> {
                        openAccessibilitySettings()
                        result.success(null)
                    }

                    METHOD_IS_USAGE_ACCESS_GRANTED -> {
                        result.success(UsageStatsReporter.hasUsageStatsPermission(this))
                    }

                    METHOD_OPEN_USAGE_ACCESS_SETTINGS -> {
                        openUsageAccessSettings()
                        result.success(null)
                    }

                    METHOD_IS_DEVICE_ADMIN_ENABLED -> {
                        result.success(isDeviceAdminEnabled())
                    }

                    METHOD_REQUEST_DEVICE_ADMIN -> {
                        requestDeviceAdmin()
                        result.success(null)
                    }

                    METHOD_OPEN_DEVICE_ADMIN_SETTINGS -> {
                        openDeviceAdminSettings()
                        result.success(null)
                    }

                    METHOD_HAS_PARENTAL_PIN -> {
                        result.success(ParentalPolicyStore.hasPin(this))
                    }

                    METHOD_SET_PARENTAL_PIN -> {
                        val pin = call.argument<String>(ARG_PIN).orEmpty()
                        result.success(ParentalPolicyStore.setPin(this, pin))
                    }

                    METHOD_VERIFY_PARENTAL_PIN -> {
                        val pin = call.argument<String>(ARG_PIN).orEmpty()
                        result.success(ParentalPolicyStore.verifyPin(this, pin))
                    }

                    METHOD_CHANGE_PARENTAL_PIN -> {
                        val oldPin = call.argument<String>(ARG_OLD_PIN).orEmpty()
                        val newPin = call.argument<String>(ARG_NEW_PIN).orEmpty()
                        val hasPin = ParentalPolicyStore.hasPin(this)
                        if (hasPin && !ParentalPolicyStore.verifyPin(this, oldPin)) {
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        result.success(ParentalPolicyStore.setPin(this, newPin))
                    }

                    METHOD_CLEAR_PARENTAL_PIN -> {
                        val pin = call.argument<String>(ARG_PIN).orEmpty()
                        if (!ParentalPolicyStore.verifyPin(this, pin)) {
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        ParentalPolicyStore.clearPin(this)
                        result.success(true)
                    }

                    METHOD_GET_SECURITY_SETTINGS -> {
                        result.success(
                            ParentalPolicyStore.settingsToMap(
                                ParentalPolicyStore.getSettings(this)
                            )
                        )
                    }

                    METHOD_UPDATE_SECURITY_SETTINGS -> {
                        val rawSettings = call.argument<Map<String, Any?>>(ARG_SETTINGS).orEmpty()
                        val current = ParentalPolicyStore.getSettings(this)
                        val merged = current.copy(
                            blockSettingsPackages = rawSettings["blockSettingsPackages"] as? Boolean
                                ?: current.blockSettingsPackages,
                            protectUninstallFlow = rawSettings["protectUninstallFlow"] as? Boolean
                                ?: current.protectUninstallFlow,
                            protectionEnabled = rawSettings["protectionEnabled"] as? Boolean
                                ?: current.protectionEnabled
                        )
                        ParentalPolicyStore.updateSettings(this, merged)
                        syncProtection()
                        result.success(ParentalPolicyStore.settingsToMap(merged))
                    }

                    METHOD_SET_PROTECTION_ENABLED -> {
                        val enabled = call.argument<Boolean>(ARG_ENABLED) ?: true
                        ParentalPolicyStore.setProtectionEnabled(this, enabled)
                        syncProtection()
                        result.success(null)
                    }

                    METHOD_IS_PROTECTION_ENABLED -> {
                        result.success(ParentalPolicyStore.isProtectionEnabled(this))
                    }

                    METHOD_START_PROTECTION_SERVICE -> {
                        ProtectionForegroundService.start(this)
                        result.success(null)
                    }

                    METHOD_STOP_PROTECTION_SERVICE -> {
                        ProtectionForegroundService.stop(this)
                        AppBlockVpnService.stop(this)
                        result.success(null)
                    }

                    METHOD_IS_PROTECTION_SERVICE_RUNNING -> {
                        result.success(ProtectionForegroundService.isRunning())
                    }

                    METHOD_GET_PROTECTION_STATUS -> {
                        result.success(buildProtectionStatus())
                    }

                    METHOD_GET_SECURITY_EVENTS -> {
                        val limit = call.argument<Int>(ARG_LIMIT) ?: 120
                        result.success(
                            ParentalPolicyStore.getEvents(this, limit).map(ParentalPolicyStore::eventToMap)
                        )
                    }

                    METHOD_CLEAR_SECURITY_EVENTS -> {
                        ParentalPolicyStore.clearEvents(this)
                        result.success(null)
                    }

                    METHOD_GET_USAGE_REPORT -> {
                        val days = call.argument<Int>(ARG_DAYS) ?: 7
                        val packageNames = call.argument<List<String>>(ARG_PACKAGE_NAMES).orEmpty().toSet()
                        result.success(
                            UsageStatsReporter.getUsageReport(
                                context = this,
                                days = days,
                                packageFilter = packageNames
                            )
                        )
                    }

                    METHOD_REQUEST_VPN_PERMISSION -> {
                        requestVpnPermission(result)
                    }

                    METHOD_START_VPN_BLOCKING -> {
                        AppBlockVpnService.start(this)
                        result.success(null)
                    }

                    METHOD_STOP_VPN_BLOCKING -> {
                        AppBlockVpnService.stop(this)
                        result.success(null)
                    }

                    METHOD_IS_VPN_BLOCKING_ACTIVE -> {
                        result.success(AppBlockVpnService.isRunning())
                    }

                    METHOD_SYNC_PROTECTION -> {
                        syncProtection()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Method channel error: ${call.method}", error)
                runCatching {
                    result.error("NATIVE_ERROR", error.message ?: "Native error", null)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            val granted = resultCode == Activity.RESULT_OK
            if (granted) {
                AppBlockVpnService.sync(this)
            }
            pendingVpnPermissionResult?.success(granted)
            pendingVpnPermissionResult = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun extractPackageName(call: MethodCall): String? {
        val args = call.arguments
        return when (args) {
            is String -> args
            is Map<*, *> -> args[ARG_PACKAGE_NAME] as? String
            else -> call.argument<String>(ARG_PACKAGE_NAME)
        }
    }

    private fun syncProtection() {
        runCatching {
            ProtectionForegroundService.sync(this)
        }.onFailure { error ->
            Log.e(TAG, "syncProtection failed", error)
        }
    }

    private fun requestVpnPermission(result: MethodChannel.Result) {
        if (pendingVpnPermissionResult != null) {
            result.error("IN_PROGRESS", "Vpn permission request already running", null)
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            AppBlockVpnService.sync(this)
            result.success(true)
            return
        }
        pendingVpnPermissionResult = result
        startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST_CODE)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return manager.isAdminActive(ParentalDeviceAdminReceiver.componentName(this))
    }

    private fun requestDeviceAdmin() {
        if (isDeviceAdminEnabled()) {
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ParentalDeviceAdminReceiver.componentName(this@MainActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permite proteger desinstalacion, ajustes y reglas de autocontrol."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun openDeviceAdminSettings() {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun buildProtectionStatus(): Map<String, Any> {
        return mapOf(
            "accessibilityEnabled" to isAccessibilityServiceEnabled(),
            "usageAccessGranted" to UsageStatsReporter.hasUsageStatsPermission(this),
            "deviceAdminEnabled" to isDeviceAdminEnabled(),
            "vpnBlockingActive" to AppBlockVpnService.isRunning(),
            "vpnPermissionGranted" to (VpnService.prepare(this) == null),
            "protectionServiceRunning" to ProtectionForegroundService.isRunning(),
            "protectionEnabled" to ParentalPolicyStore.isProtectionEnabled(this)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
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

    private companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL_NAME = "com.evolcorp.control_parental/blocked_apps"
        private const val METHOD_BLOCK = "block"
        private const val METHOD_UNBLOCK = "unblock"
        private const val METHOD_BLOCK_APP = "blockApp"
        private const val METHOD_UNBLOCK_APP = "unblockApp"
        private const val METHOD_IS_BLOCKED = "isBlocked"
        private const val METHOD_GET_BLOCKED_APPS = "getBlockedApps"
        private const val METHOD_SET_BLOCKED_APPS = "setBlockedApps"
        private const val METHOD_GET_APP_RULES = "getAppRules"
        private const val METHOD_UPSERT_APP_RULE = "upsertAppRule"
        private const val METHOD_REMOVE_APP_RULE = "removeAppRule"
        private const val METHOD_IS_ACCESSIBILITY_ENABLED = "isAccessibilityEnabled"
        private const val METHOD_OPEN_ACCESSIBILITY_SETTINGS = "openAccessibilitySettings"
        private const val METHOD_IS_USAGE_ACCESS_GRANTED = "isUsageAccessGranted"
        private const val METHOD_OPEN_USAGE_ACCESS_SETTINGS = "openUsageAccessSettings"
        private const val METHOD_IS_DEVICE_ADMIN_ENABLED = "isDeviceAdminEnabled"
        private const val METHOD_REQUEST_DEVICE_ADMIN = "requestDeviceAdmin"
        private const val METHOD_OPEN_DEVICE_ADMIN_SETTINGS = "openDeviceAdminSettings"
        private const val METHOD_HAS_PARENTAL_PIN = "hasParentalPin"
        private const val METHOD_SET_PARENTAL_PIN = "setParentalPin"
        private const val METHOD_VERIFY_PARENTAL_PIN = "verifyParentalPin"
        private const val METHOD_CHANGE_PARENTAL_PIN = "changeParentalPin"
        private const val METHOD_CLEAR_PARENTAL_PIN = "clearParentalPin"
        private const val METHOD_GET_SECURITY_SETTINGS = "getSecuritySettings"
        private const val METHOD_UPDATE_SECURITY_SETTINGS = "updateSecuritySettings"
        private const val METHOD_SET_PROTECTION_ENABLED = "setProtectionEnabled"
        private const val METHOD_IS_PROTECTION_ENABLED = "isProtectionEnabled"
        private const val METHOD_START_PROTECTION_SERVICE = "startProtectionService"
        private const val METHOD_STOP_PROTECTION_SERVICE = "stopProtectionService"
        private const val METHOD_IS_PROTECTION_SERVICE_RUNNING = "isProtectionServiceRunning"
        private const val METHOD_GET_PROTECTION_STATUS = "getProtectionStatus"
        private const val METHOD_GET_SECURITY_EVENTS = "getSecurityEvents"
        private const val METHOD_CLEAR_SECURITY_EVENTS = "clearSecurityEvents"
        private const val METHOD_GET_USAGE_REPORT = "getUsageReport"
        private const val METHOD_REQUEST_VPN_PERMISSION = "requestVpnPermission"
        private const val METHOD_START_VPN_BLOCKING = "startVpnBlocking"
        private const val METHOD_STOP_VPN_BLOCKING = "stopVpnBlocking"
        private const val METHOD_IS_VPN_BLOCKING_ACTIVE = "isVpnBlockingActive"
        private const val METHOD_SYNC_PROTECTION = "syncProtection"
        private const val ARG_PACKAGE_NAME = "packageName"
        private const val ARG_PACKAGE_NAMES = "packageNames"
        private const val ARG_RULE = "rule"
        private const val ARG_SETTINGS = "settings"
        private const val ARG_PIN = "pin"
        private const val ARG_OLD_PIN = "oldPin"
        private const val ARG_NEW_PIN = "newPin"
        private const val ARG_ENABLED = "enabled"
        private const val ARG_LIMIT = "limit"
        private const val ARG_DAYS = "days"
        private const val VPN_PERMISSION_REQUEST_CODE = 7412
    }
}
