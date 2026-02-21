class ProtectionStatus {
  const ProtectionStatus({
    this.accessibilityEnabled = false,
    this.usageAccessGranted = false,
    this.deviceAdminEnabled = false,
    this.vpnBlockingActive = false,
    this.vpnPermissionGranted = false,
    this.protectionServiceRunning = false,
    this.protectionEnabled = true,
  });

  final bool accessibilityEnabled;
  final bool usageAccessGranted;
  final bool deviceAdminEnabled;
  final bool vpnBlockingActive;
  final bool vpnPermissionGranted;
  final bool protectionServiceRunning;
  final bool protectionEnabled;

  static ProtectionStatus fromMap(Map<Object?, Object?> map) {
    return ProtectionStatus(
      accessibilityEnabled: map['accessibilityEnabled'] as bool? ?? false,
      usageAccessGranted: map['usageAccessGranted'] as bool? ?? false,
      deviceAdminEnabled: map['deviceAdminEnabled'] as bool? ?? false,
      vpnBlockingActive: map['vpnBlockingActive'] as bool? ?? false,
      vpnPermissionGranted: map['vpnPermissionGranted'] as bool? ?? false,
      protectionServiceRunning:
          map['protectionServiceRunning'] as bool? ?? false,
      protectionEnabled: map['protectionEnabled'] as bool? ?? true,
    );
  }
}
