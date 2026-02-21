import '../../domain/repositories/blocked_apps_repository.dart';
import '../datasources/blocked_apps_data_source.dart';

class BlockedAppsRepositoryImpl implements BlockedAppsRepository {
  BlockedAppsRepositoryImpl(this._dataSource);

  final BlockedAppsDataSource _dataSource;

  @override
  Future<void> block(String packageName) {
    return _dataSource.block(packageName);
  }

  @override
  Future<void> unblock(String packageName) {
    return _dataSource.unblock(packageName);
  }

  @override
  Future<void> blockTemporarily(String packageName, Duration duration) {
    return _dataSource.blockTemporarily(packageName, duration);
  }

  @override
  Future<bool> isBlocked(String packageName) {
    return _dataSource.isBlocked(packageName);
  }

  @override
  Future<Set<String>> getBlockedApps() {
    return _dataSource.getBlockedApps();
  }

  @override
  Future<void> setBlockedApps(Set<String> packageNames) {
    return _dataSource.setBlockedApps(packageNames);
  }

  @override
  Future<bool> isAccessibilityServiceEnabled() {
    return _dataSource.isAccessibilityServiceEnabled();
  }

  @override
  Future<bool> requestVpnPermission() {
    return _dataSource.requestVpnPermission();
  }

  @override
  Future<void> startVpnBlocking() {
    return _dataSource.startVpnBlocking();
  }

  @override
  Future<void> stopVpnBlocking() {
    return _dataSource.stopVpnBlocking();
  }

  @override
  Future<bool> isVpnBlockingActive() {
    return _dataSource.isVpnBlockingActive();
  }

  @override
  Future<void> syncProtection() {
    return _dataSource.syncProtection();
  }
}
