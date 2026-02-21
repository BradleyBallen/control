import 'package:flutter/foundation.dart';

import '../../../apps/domain/entities/installed_app.dart';
import '../../../apps/domain/usecases/get_installed_apps_use_case.dart';
import '../../data/native_parental_data_source.dart';
import '../../domain/models/app_control_rule.dart';
import '../../domain/models/protection_status.dart';
import '../../domain/models/security_event.dart';
import '../../domain/models/security_settings.dart';
import '../../domain/models/usage_report_entry.dart';

enum AuthStage { checking, unlocked }

class ParentalDashboardController extends ChangeNotifier {
  ParentalDashboardController(
    this._getInstalledAppsUseCase,
    this._native,
  );

  final GetInstalledAppsUseCase _getInstalledAppsUseCase;
  final ParentalDataSource _native;

  AuthStage _authStage = AuthStage.checking;
  bool _isLoading = false;
  bool _isRefreshingReports = false;
  String? _errorMessage;
  List<InstalledApp> _apps = const [];
  Set<String> _alwaysBlockedPackages = <String>{};
  Map<String, AppControlRule> _rulesByPackage = <String, AppControlRule>{};
  SecuritySettings _securitySettings = const SecuritySettings();
  ProtectionStatus _protectionStatus = const ProtectionStatus();
  List<SecurityEvent> _securityEvents = const [];
  List<UsageReportEntry> _usageReport = const [];
  int _reportDays = 7;

  AuthStage get authStage => _authStage;
  bool get isLoading => _isLoading;
  bool get isRefreshingReports => _isRefreshingReports;
  String? get errorMessage => _errorMessage;
  List<InstalledApp> get apps => List<InstalledApp>.unmodifiable(_apps);
  Set<String> get alwaysBlockedPackages =>
      Set<String>.unmodifiable(_alwaysBlockedPackages);
  Map<String, AppControlRule> get rulesByPackage =>
      Map<String, AppControlRule>.unmodifiable(_rulesByPackage);
  SecuritySettings get securitySettings => _securitySettings;
  ProtectionStatus get protectionStatus => _protectionStatus;
  List<SecurityEvent> get securityEvents =>
      List<SecurityEvent>.unmodifiable(_securityEvents);
  List<UsageReportEntry> get usageReport =>
      List<UsageReportEntry>.unmodifiable(_usageReport);
  int get reportDays => _reportDays;

  Future<void> initialize() async {
    _authStage = AuthStage.unlocked;
    notifyListeners();
    await loadAllData();
  }

  Future<bool> createPin(String pin) async {
    final success = await _native.setParentalPin(pin);
    if (!success) {
      return false;
    }
    _authStage = AuthStage.unlocked;
    notifyListeners();
    await loadAllData();
    return true;
  }

  Future<bool> unlock(String pin) async {
    final success = await _native.verifyParentalPin(pin);
    if (!success) {
      return false;
    }
    _authStage = AuthStage.unlocked;
    notifyListeners();
    await loadAllData();
    return true;
  }

  void lockSession() {
    // El panel funciona en modo autocontrol y no se bloquea por sesion.
    _authStage = AuthStage.unlocked;
  }

  Future<void> loadAllData() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final appsFuture = _getInstalledAppsUseCase();
      final blockedFuture = _native.getBlockedApps();
      final rulesFuture = _native.getAppRules();
      final settingsFuture = _native.getSecuritySettings();
      final statusFuture = _native.getProtectionStatus();
      final eventsFuture = _native.getSecurityEvents();
      final usageFuture = _native.getUsageReport(days: _reportDays);

      _apps = await appsFuture;
      _alwaysBlockedPackages = (await blockedFuture)
          .map((pkg) => pkg.toLowerCase())
          .toSet();
      _rulesByPackage = {
        for (final rule in await rulesFuture) rule.packageName: rule,
      };
      _securitySettings = await settingsFuture;
      _protectionStatus = await statusFuture;
      _securityEvents = await eventsFuture;
      _usageReport = await usageFuture;
      _errorMessage = null;
    } catch (_) {
      _errorMessage =
          'No se pudo cargar la configuracion local de autocontrol.';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  bool isAlwaysBlocked(String packageName) {
    return _alwaysBlockedPackages.contains(packageName.toLowerCase());
  }

  AppControlRule? ruleFor(String packageName) {
    return _rulesByPackage[packageName.toLowerCase()];
  }

  Future<void> setAlwaysBlocked(String packageName, bool blocked) async {
    final normalizedPackage = packageName.toLowerCase();
    final nextSet = Set<String>.from(_alwaysBlockedPackages);
    if (blocked) {
      nextSet.add(normalizedPackage);
    } else {
      nextSet.remove(normalizedPackage);
    }
    _alwaysBlockedPackages = nextSet;
    notifyListeners();
    try {
      await _native.setBlockedApps(nextSet);
      await _native.syncProtection();
      await refreshProtectionStatus();
    } catch (_) {
      _errorMessage = 'No se pudo actualizar bloqueo permanente.';
      notifyListeners();
    }
  }

  Future<void> saveRule(AppControlRule rule) async {
    final normalizedRule = rule.copyWith(packageName: rule.packageName.toLowerCase());
    _rulesByPackage = {
      ..._rulesByPackage,
      normalizedRule.packageName: normalizedRule,
    };
    notifyListeners();
    try {
      await _native.upsertAppRule(normalizedRule);
      await _native.syncProtection();
      await refreshProtectionStatus();
    } catch (_) {
      _errorMessage = 'No se pudo guardar la regla.';
      notifyListeners();
    }
  }

  Future<void> removeRule(String packageName) async {
    final normalizedPackage = packageName.toLowerCase();
    final nextRules = Map<String, AppControlRule>.from(_rulesByPackage)
      ..remove(normalizedPackage);
    _rulesByPackage = nextRules;
    notifyListeners();
    try {
      await _native.removeAppRule(normalizedPackage);
      await _native.syncProtection();
      await refreshProtectionStatus();
    } catch (_) {
      _errorMessage = 'No se pudo eliminar la regla.';
      notifyListeners();
    }
  }

  Future<void> refreshProtectionStatus() async {
    try {
      _protectionStatus = await _native.getProtectionStatus();
      notifyListeners();
    } catch (_) {
      _errorMessage = 'No se pudo consultar el estado de proteccion.';
      notifyListeners();
    }
  }

  Future<void> syncProtection() async {
    try {
      await _native.syncProtection();
      await refreshProtectionStatus();
    } catch (_) {
      _errorMessage = 'No se pudo sincronizar la proteccion.';
      notifyListeners();
    }
  }

  Future<void> applySecuritySettings(SecuritySettings settings) async {
    try {
      _securitySettings = await _native.updateSecuritySettings(settings);
      await refreshProtectionStatus();
      notifyListeners();
    } catch (_) {
      _errorMessage = 'No se pudo actualizar la configuracion de seguridad.';
      notifyListeners();
    }
  }

  Future<void> setProtectionEnabled(bool enabled) async {
    await applySecuritySettings(
      _securitySettings.copyWith(protectionEnabled: enabled),
    );
  }

  Future<void> requestVpnPermissionAndStart() async {
    final granted = await _native.requestVpnPermission();
    if (granted) {
      await _native.startVpnBlocking();
      await refreshProtectionStatus();
    }
  }

  Future<void> stopVpnBlocking() async {
    await _native.stopVpnBlocking();
    await refreshProtectionStatus();
  }

  Future<void> openAccessibilitySettings() => _native.openAccessibilitySettings();
  Future<void> openUsageAccessSettings() => _native.openUsageAccessSettings();
  Future<void> requestDeviceAdmin() => _native.requestDeviceAdmin();
  Future<void> openDeviceAdminSettings() => _native.openDeviceAdminSettings();

  Future<void> changePin({
    required String oldPin,
    required String newPin,
  }) async {
    final changed = await _native.changeParentalPin(
      oldPin: oldPin,
      newPin: newPin,
    );
    if (!changed) {
      _errorMessage = 'No se pudo cambiar el PIN.';
      notifyListeners();
    }
  }

  Future<void> refreshReports({int? days}) async {
    _isRefreshingReports = true;
    if (days != null) {
      _reportDays = days;
    }
    notifyListeners();
    try {
      _usageReport = await _native.getUsageReport(days: _reportDays);
      _securityEvents = await _native.getSecurityEvents();
      _errorMessage = null;
    } catch (_) {
      _errorMessage = 'No se pudieron actualizar los reportes.';
    } finally {
      _isRefreshingReports = false;
      notifyListeners();
    }
  }

  Future<void> clearSecurityEvents() async {
    await _native.clearSecurityEvents();
    _securityEvents = const [];
    notifyListeners();
  }

  bool get usageAccessGranted => _protectionStatus.usageAccessGranted;

  Map<String, int> get usageMinutesTodayByPackage {
    final now = DateTime.now();
    final usage = <String, int>{};
    for (final entry in _usageReport) {
      final day = DateTime.fromMillisecondsSinceEpoch(entry.dayStartMs);
      if (!_isSameDay(day, now)) {
        continue;
      }
      final packageName = entry.packageName.toLowerCase();
      usage[packageName] = (usage[packageName] ?? 0) + entry.usageMinutes;
    }
    return usage;
  }

  int usageMinutesTodayFor(String packageName) {
    return usageMinutesTodayByPackage[packageName.toLowerCase()] ?? 0;
  }

  bool _isSameDay(DateTime a, DateTime b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }
}
