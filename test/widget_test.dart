import 'package:control_parental/main.dart';
import 'package:control_parental/src/apps/domain/entities/installed_app.dart';
import 'package:control_parental/src/apps/domain/repositories/installed_apps_repository.dart';
import 'package:control_parental/src/apps/domain/usecases/get_installed_apps_use_case.dart';
import 'package:control_parental/src/protection/data/native_parental_data_source.dart';
import 'package:control_parental/src/protection/domain/models/app_control_rule.dart';
import 'package:control_parental/src/protection/domain/models/protection_status.dart';
import 'package:control_parental/src/protection/domain/models/security_event.dart';
import 'package:control_parental/src/protection/domain/models/security_settings.dart';
import 'package:control_parental/src/protection/domain/models/usage_report_entry.dart';
import 'package:control_parental/src/protection/presentation/controllers/parental_dashboard_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('muestra el panel de autocontrol sin pedir PIN', (tester) async {
    final controller = ParentalDashboardController(
      GetInstalledAppsUseCase(_FakeInstalledAppsRepository()),
      _FakeParentalDataSource(),
    );

    await tester.pumpWidget(MyApp(controller: controller));
    await tester.pumpAndSettle();

    expect(find.text('Mi enfoque'), findsOneWidget);
    expect(find.text('Estado de autocontrol'), findsOneWidget);
  });
}

class _FakeParentalDataSource implements ParentalDataSource {
  bool _hasPin = false;
  final Set<String> _blocked = <String>{};
  final Map<String, AppControlRule> _rules = <String, AppControlRule>{};
  SecuritySettings _settings = const SecuritySettings();

  @override
  Future<void> clearSecurityEvents() async {}

  @override
  Future<bool> changeParentalPin({
    required String oldPin,
    required String newPin,
  }) async {
    _hasPin = newPin.isNotEmpty;
    return true;
  }

  @override
  Future<List<AppControlRule>> getAppRules() async => _rules.values.toList();

  @override
  Future<Set<String>> getBlockedApps() async => _blocked;

  @override
  Future<ProtectionStatus> getProtectionStatus() async =>
      const ProtectionStatus(
        accessibilityEnabled: true,
        usageAccessGranted: true,
        deviceAdminEnabled: true,
        vpnBlockingActive: false,
        vpnPermissionGranted: true,
        protectionServiceRunning: true,
        protectionEnabled: true,
      );

  @override
  Future<List<SecurityEvent>> getSecurityEvents({int limit = 120}) async =>
      const [];

  @override
  Future<SecuritySettings> getSecuritySettings() async => _settings;

  @override
  Future<List<UsageReportEntry>> getUsageReport({
    int days = 7,
    Set<String> packageNames = const <String>{},
  }) async => const [];

  @override
  Future<bool> hasParentalPin() async => _hasPin;

  @override
  Future<void> openAccessibilitySettings() async {}

  @override
  Future<void> openDeviceAdminSettings() async {}

  @override
  Future<void> openUsageAccessSettings() async {}

  @override
  Future<void> removeAppRule(String packageName) async {
    _rules.remove(packageName.toLowerCase());
  }

  @override
  Future<void> requestDeviceAdmin() async {}

  @override
  Future<bool> requestVpnPermission() async => true;

  @override
  Future<bool> setParentalPin(String pin) async {
    _hasPin = pin.length >= 4;
    return _hasPin;
  }

  @override
  Future<void> setBlockedApps(Set<String> packageNames) async {
    _blocked
      ..clear()
      ..addAll(packageNames);
  }

  @override
  Future<void> setProtectionEnabled(bool enabled) async {
    _settings = _settings.copyWith(protectionEnabled: enabled);
  }

  @override
  Future<void> startProtectionService() async {}

  @override
  Future<void> startVpnBlocking() async {}

  @override
  Future<void> stopProtectionService() async {}

  @override
  Future<void> stopVpnBlocking() async {}

  @override
  Future<void> syncProtection() async {}

  @override
  Future<void> upsertAppRule(AppControlRule rule) async {
    _rules[rule.packageName.toLowerCase()] = rule;
  }

  @override
  Future<SecuritySettings> updateSecuritySettings(
    SecuritySettings settings,
  ) async {
    _settings = settings;
    return _settings;
  }

  @override
  Future<bool> verifyParentalPin(String pin) async => _hasPin && pin == '1234';
}

class _FakeInstalledAppsRepository implements InstalledAppsRepository {
  @override
  Future<List<InstalledApp>> getInstalledApps({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  }) async {
    return const [
      InstalledApp(
        appName: 'YouTube',
        packageName: 'com.google.android.youtube',
      ),
    ];
  }
}
