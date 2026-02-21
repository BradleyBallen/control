import 'package:flutter/services.dart';

import '../domain/models/app_control_rule.dart';
import '../domain/models/protection_status.dart';
import '../domain/models/security_event.dart';
import '../domain/models/security_settings.dart';
import '../domain/models/usage_report_entry.dart';

abstract class ParentalDataSource {
  Future<bool> hasParentalPin();
  Future<bool> setParentalPin(String pin);
  Future<bool> verifyParentalPin(String pin);
  Future<bool> changeParentalPin({
    required String oldPin,
    required String newPin,
  });
  Future<Set<String>> getBlockedApps();
  Future<void> setBlockedApps(Set<String> packageNames);
  Future<List<AppControlRule>> getAppRules();
  Future<void> upsertAppRule(AppControlRule rule);
  Future<void> removeAppRule(String packageName);
  Future<SecuritySettings> getSecuritySettings();
  Future<SecuritySettings> updateSecuritySettings(SecuritySettings settings);
  Future<void> setProtectionEnabled(bool enabled);
  Future<void> syncProtection();
  Future<void> startProtectionService();
  Future<void> stopProtectionService();
  Future<ProtectionStatus> getProtectionStatus();
  Future<bool> requestVpnPermission();
  Future<void> startVpnBlocking();
  Future<void> stopVpnBlocking();
  Future<void> openAccessibilitySettings();
  Future<void> openUsageAccessSettings();
  Future<void> requestDeviceAdmin();
  Future<void> openDeviceAdminSettings();
  Future<List<SecurityEvent>> getSecurityEvents({int limit = 120});
  Future<void> clearSecurityEvents();
  Future<List<UsageReportEntry>> getUsageReport({
    int days = 7,
    Set<String> packageNames = const <String>{},
  });
}

class NativeParentalDataSource implements ParentalDataSource {
  static const MethodChannel _channel = MethodChannel(
    'com.EvolCorp.control_parental/blocked_apps',
  );

  @override
  Future<bool> hasParentalPin() async {
    final result = await _channel.invokeMethod<bool>('hasParentalPin');
    return result ?? false;
  }

  @override
  Future<bool> setParentalPin(String pin) async {
    final result = await _channel.invokeMethod<bool>('setParentalPin', {
      'pin': pin,
    });
    return result ?? false;
  }

  @override
  Future<bool> verifyParentalPin(String pin) async {
    final result = await _channel.invokeMethod<bool>('verifyParentalPin', {
      'pin': pin,
    });
    return result ?? false;
  }

  @override
  Future<bool> changeParentalPin({
    required String oldPin,
    required String newPin,
  }) async {
    final result = await _channel.invokeMethod<bool>('changeParentalPin', {
      'oldPin': oldPin,
      'newPin': newPin,
    });
    return result ?? false;
  }

  @override
  Future<Set<String>> getBlockedApps() async {
    final result = await _channel.invokeListMethod<String>('getBlockedApps');
    return (result ?? const <String>[]).toSet();
  }

  @override
  Future<void> setBlockedApps(Set<String> packageNames) {
    return _channel.invokeMethod<void>('setBlockedApps', {
      'packageNames': packageNames.toList(growable: false),
    });
  }

  @override
  Future<List<AppControlRule>> getAppRules() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getAppRules');
    return (result ?? const <dynamic>[])
        .whereType<Map<Object?, Object?>>()
        .map(AppControlRule.fromMap)
        .where((rule) => rule.packageName.isNotEmpty)
        .toList(growable: false);
  }

  @override
  Future<void> upsertAppRule(AppControlRule rule) {
    return _channel.invokeMethod<void>('upsertAppRule', {
      'rule': rule.toMap(),
    });
  }

  @override
  Future<void> removeAppRule(String packageName) {
    return _channel.invokeMethod<void>('removeAppRule', {
      'packageName': packageName,
    });
  }

  @override
  Future<SecuritySettings> getSecuritySettings() async {
    final result = await _channel.invokeMethod<dynamic>('getSecuritySettings');
    final map = result is Map<Object?, Object?>
        ? result
        : <Object?, Object?>{};
    return SecuritySettings.fromMap(map);
  }

  @override
  Future<SecuritySettings> updateSecuritySettings(SecuritySettings settings) async {
    final result = await _channel.invokeMethod<dynamic>('updateSecuritySettings', {
      'settings': settings.toMap(),
    });
    final map = result is Map<Object?, Object?>
        ? result
        : <Object?, Object?>{};
    return SecuritySettings.fromMap(map);
  }

  @override
  Future<void> setProtectionEnabled(bool enabled) {
    return _channel.invokeMethod<void>('setProtectionEnabled', {
      'enabled': enabled,
    });
  }

  @override
  Future<void> syncProtection() {
    return _channel.invokeMethod<void>('syncProtection');
  }

  @override
  Future<void> startProtectionService() {
    return _channel.invokeMethod<void>('startProtectionService');
  }

  @override
  Future<void> stopProtectionService() {
    return _channel.invokeMethod<void>('stopProtectionService');
  }

  @override
  Future<ProtectionStatus> getProtectionStatus() async {
    final result = await _channel.invokeMethod<dynamic>('getProtectionStatus');
    final map = result is Map<Object?, Object?>
        ? result
        : <Object?, Object?>{};
    return ProtectionStatus.fromMap(map);
  }

  @override
  Future<bool> requestVpnPermission() async {
    final result = await _channel.invokeMethod<bool>('requestVpnPermission');
    return result ?? false;
  }

  @override
  Future<void> startVpnBlocking() {
    return _channel.invokeMethod<void>('startVpnBlocking');
  }

  @override
  Future<void> stopVpnBlocking() {
    return _channel.invokeMethod<void>('stopVpnBlocking');
  }

  @override
  Future<void> openAccessibilitySettings() {
    return _channel.invokeMethod<void>('openAccessibilitySettings');
  }

  @override
  Future<void> openUsageAccessSettings() {
    return _channel.invokeMethod<void>('openUsageAccessSettings');
  }

  @override
  Future<void> requestDeviceAdmin() {
    return _channel.invokeMethod<void>('requestDeviceAdmin');
  }

  @override
  Future<void> openDeviceAdminSettings() {
    return _channel.invokeMethod<void>('openDeviceAdminSettings');
  }

  @override
  Future<List<SecurityEvent>> getSecurityEvents({int limit = 120}) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'getSecurityEvents',
      {'limit': limit},
    );
    return (result ?? const <dynamic>[])
        .whereType<Map<Object?, Object?>>()
        .map(SecurityEvent.fromMap)
        .toList(growable: false);
  }

  @override
  Future<void> clearSecurityEvents() {
    return _channel.invokeMethod<void>('clearSecurityEvents');
  }

  @override
  Future<List<UsageReportEntry>> getUsageReport({
    int days = 7,
    Set<String> packageNames = const <String>{},
  }) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'getUsageReport',
      {
        'days': days,
        'packageNames': packageNames.toList(growable: false),
      },
    );
    return (result ?? const <dynamic>[])
        .whereType<Map<Object?, Object?>>()
        .map(UsageReportEntry.fromMap)
        .toList(growable: false);
  }
}
