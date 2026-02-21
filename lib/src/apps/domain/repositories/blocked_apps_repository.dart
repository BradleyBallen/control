abstract class BlockedAppsRepository {
  Future<void> block(String packageName);

  Future<void> unblock(String packageName);

  Future<void> blockTemporarily(String packageName, Duration duration);

  Future<bool> isBlocked(String packageName);

  Future<Set<String>> getBlockedApps();

  Future<void> setBlockedApps(Set<String> packageNames);

  Future<bool> isAccessibilityServiceEnabled();

  Future<bool> requestVpnPermission();

  Future<void> startVpnBlocking();

  Future<void> stopVpnBlocking();

  Future<bool> isVpnBlockingActive();

  Future<void> syncProtection();
}
