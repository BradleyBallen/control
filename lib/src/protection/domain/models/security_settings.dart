class SecuritySettings {
  const SecuritySettings({
    this.blockSettingsPackages = true,
    this.protectUninstallFlow = true,
    this.protectionEnabled = true,
  });

  final bool blockSettingsPackages;
  final bool protectUninstallFlow;
  final bool protectionEnabled;

  SecuritySettings copyWith({
    bool? blockSettingsPackages,
    bool? protectUninstallFlow,
    bool? protectionEnabled,
  }) {
    return SecuritySettings(
      blockSettingsPackages: blockSettingsPackages ?? this.blockSettingsPackages,
      protectUninstallFlow: protectUninstallFlow ?? this.protectUninstallFlow,
      protectionEnabled: protectionEnabled ?? this.protectionEnabled,
    );
  }

  Map<String, Object> toMap() {
    return {
      'blockSettingsPackages': blockSettingsPackages,
      'protectUninstallFlow': protectUninstallFlow,
      'protectionEnabled': protectionEnabled,
    };
  }

  static SecuritySettings fromMap(Map<Object?, Object?> map) {
    return SecuritySettings(
      blockSettingsPackages: map['blockSettingsPackages'] as bool? ?? true,
      protectUninstallFlow: map['protectUninstallFlow'] as bool? ?? true,
      protectionEnabled: map['protectionEnabled'] as bool? ?? true,
    );
  }
}
