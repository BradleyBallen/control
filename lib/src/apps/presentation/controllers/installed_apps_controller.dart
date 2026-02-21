import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../domain/entities/installed_app.dart';
import '../../domain/repositories/blocked_apps_repository.dart';
import '../../domain/usecases/get_installed_apps_use_case.dart';

enum InstalledAppsStatus { initial, loading, loaded, error }

class InstalledAppsController extends ChangeNotifier {
  InstalledAppsController(
    this._getInstalledAppsUseCase, {
    required BlockedAppsRepository blockedAppsRepository,
  }) : _blockedAppsRepository = blockedAppsRepository;

  final GetInstalledAppsUseCase _getInstalledAppsUseCase;
  final BlockedAppsRepository _blockedAppsRepository;

  InstalledAppsStatus _status = InstalledAppsStatus.initial;
  List<InstalledApp> _apps = const [];
  final Set<String> _selectedPackages = <String>{};
  final Set<String> _blockedPackages = <String>{};
  final Map<String, Timer> _temporaryUnblockTimers = <String, Timer>{};
  bool _isAccessibilityEnabled = false;
  bool _isVpnBlockingActive = false;
  bool _isApplyingProtection = false;
  String? _errorMessage;

  InstalledAppsStatus get status => _status;
  List<InstalledApp> get apps => List<InstalledApp>.unmodifiable(_apps);
  Set<String> get selectedPackages => Set<String>.unmodifiable(_selectedPackages);
  Set<String> get blockedPackages => Set<String>.unmodifiable(_blockedPackages);
  bool get isAccessibilityEnabled => _isAccessibilityEnabled;
  bool get isVpnBlockingActive => _isVpnBlockingActive;
  bool get isApplyingProtection => _isApplyingProtection;
  String? get errorMessage => _errorMessage;

  Future<void> loadApps() async {
    _status = InstalledAppsStatus.loading;
    _errorMessage = null;
    notifyListeners();

    try {
      final loadedApps = await _getInstalledAppsUseCase();
      final blockedApps = await _blockedAppsRepository.getBlockedApps();
      await _refreshProtectionStatus();
      _apps = loadedApps;
      _blockedPackages
        ..clear()
        ..addAll(blockedApps);
      _selectedPackages.clear();
      _keepOnlyValidPackageSets();
      _status = InstalledAppsStatus.loaded;
    } catch (error) {
      _status = InstalledAppsStatus.error;
      _errorMessage = 'No se pudieron cargar o sincronizar las apps bloqueadas.';
    }

    notifyListeners();
  }

  bool isSelected(String packageName) {
    return _selectedPackages.contains(packageName);
  }

  bool isBlocked(String packageName) {
    return _blockedPackages.contains(packageName);
  }

  void toggleSelection(String packageName) {
    if (_selectedPackages.contains(packageName)) {
      _selectedPackages.remove(packageName);
      _temporaryUnblockTimers.remove(packageName)?.cancel();
    } else {
      _selectedPackages.add(packageName);
    }
    notifyListeners();
  }

  Future<bool> applySelectedBlocks() async {
    final nextBlocked = Set<String>.from(_blockedPackages)
      ..addAll(_selectedPackages);
    if (setEquals(nextBlocked, _blockedPackages)) {
      return true;
    }

    try {
      await _blockedAppsRepository.setBlockedApps(nextBlocked);
      await _blockedAppsRepository.syncProtection();
      _blockedPackages
        ..clear()
        ..addAll(nextBlocked);
      _selectedPackages.clear();
      _errorMessage = null;
      notifyListeners();
      return true;
    } catch (_) {
      _errorMessage = 'No se pudo aplicar el bloqueo de apps.';
      notifyListeners();
      return false;
    }
  }

  Future<bool> applySelectedUnblocks() async {
    if (_selectedPackages.isEmpty) {
      return true;
    }

    final nextBlocked = Set<String>.from(_blockedPackages)
      ..removeAll(_selectedPackages);
    if (setEquals(nextBlocked, _blockedPackages)) {
      return true;
    }

    try {
      await _blockedAppsRepository.setBlockedApps(nextBlocked);
      await _blockedAppsRepository.syncProtection();
      _blockedPackages
        ..clear()
        ..addAll(nextBlocked);
      _selectedPackages.clear();
      _errorMessage = null;
      notifyListeners();
      return true;
    } catch (_) {
      _errorMessage = 'No se pudo desbloquear el acceso de apps.';
      notifyListeners();
      return false;
    }
  }

  Future<void> blockTemporarily(String packageName, Duration duration) async {
    if (duration <= Duration.zero) {
      return;
    }

    final wasBlocked = _blockedPackages.contains(packageName);
    _blockedPackages.add(packageName);
    notifyListeners();

    try {
      await _blockedAppsRepository.blockTemporarily(packageName, duration);
    } catch (_) {
      if (!wasBlocked) {
        _blockedPackages.remove(packageName);
      }
      notifyListeners();
      return;
    }

    _temporaryUnblockTimers.remove(packageName)?.cancel();
    _temporaryUnblockTimers[packageName] = Timer(duration, () {
      _temporaryUnblockTimers.remove(packageName);
      if (!wasBlocked) {
        _blockedPackages.remove(packageName);
      }
      notifyListeners();
    });
  }

  void clearSelection() {
    if (_selectedPackages.isEmpty) {
      return;
    }
    _selectedPackages.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    for (final timer in _temporaryUnblockTimers.values) {
      timer.cancel();
    }
    _temporaryUnblockTimers.clear();
    super.dispose();
  }

  void _keepOnlyValidPackageSets() {
    final availablePackages = _apps.map((app) => app.packageName).toSet();
    _selectedPackages.removeWhere((pkg) => !availablePackages.contains(pkg));
    _blockedPackages.removeWhere((pkg) => !availablePackages.contains(pkg));
  }

  Future<String?> enforceProtection() async {
    if (_isApplyingProtection) {
      return null;
    }
    _isApplyingProtection = true;
    notifyListeners();

    try {
      if (_blockedPackages.isEmpty) {
        await _blockedAppsRepository.stopVpnBlocking();
        await _refreshProtectionStatus();
        return null;
      }

      await _blockedAppsRepository.syncProtection();
      var vpnActive = await _blockedAppsRepository.isVpnBlockingActive();
      if (!vpnActive) {
        final permissionGranted = await _blockedAppsRepository.requestVpnPermission();
        if (permissionGranted) {
          await _blockedAppsRepository.startVpnBlocking();
          vpnActive = await _blockedAppsRepository.isVpnBlockingActive();
        }
      }

      _isAccessibilityEnabled = await _blockedAppsRepository
          .isAccessibilityServiceEnabled();
      _isVpnBlockingActive = vpnActive;

      if (!_isAccessibilityEnabled) {
        return 'Activa Accesibilidad para bloqueo total.';
      }
      if (!_isVpnBlockingActive) {
        return 'Acepta el permiso VPN para bloquear internet por app.';
      }
      return null;
    } catch (_) {
      return 'No se pudo activar la proteccion VPN.';
    } finally {
      _isApplyingProtection = false;
      notifyListeners();
    }
  }

  Future<void> _refreshProtectionStatus() async {
    try {
      _isAccessibilityEnabled = await _blockedAppsRepository
          .isAccessibilityServiceEnabled();
    } catch (_) {
      _isAccessibilityEnabled = false;
    }

    try {
      _isVpnBlockingActive = await _blockedAppsRepository.isVpnBlockingActive();
    } catch (_) {
      _isVpnBlockingActive = false;
    }
  }
}
