class AppControlRule {
  const AppControlRule({
    required this.packageName,
    this.alwaysBlocked = false,
    this.dailyLimitMinutes = 0,
    this.scheduleEnabled = false,
    this.scheduleStartMinute = 0,
    this.scheduleEndMinute = 1439,
    this.allowedDays = const {1, 2, 3, 4, 5, 6, 7},
    this.vpnBlockEnabled = true,
  });

  final String packageName;
  final bool alwaysBlocked;
  final int dailyLimitMinutes;
  final bool scheduleEnabled;
  final int scheduleStartMinute;
  final int scheduleEndMinute;
  final Set<int> allowedDays;
  final bool vpnBlockEnabled;

  bool get hasDailyLimit => dailyLimitMinutes > 0;
  bool get hasSchedule => scheduleEnabled;

  AppControlRule copyWith({
    String? packageName,
    bool? alwaysBlocked,
    int? dailyLimitMinutes,
    bool? scheduleEnabled,
    int? scheduleStartMinute,
    int? scheduleEndMinute,
    Set<int>? allowedDays,
    bool? vpnBlockEnabled,
  }) {
    return AppControlRule(
      packageName: packageName ?? this.packageName,
      alwaysBlocked: alwaysBlocked ?? this.alwaysBlocked,
      dailyLimitMinutes: dailyLimitMinutes ?? this.dailyLimitMinutes,
      scheduleEnabled: scheduleEnabled ?? this.scheduleEnabled,
      scheduleStartMinute: scheduleStartMinute ?? this.scheduleStartMinute,
      scheduleEndMinute: scheduleEndMinute ?? this.scheduleEndMinute,
      allowedDays: allowedDays ?? this.allowedDays,
      vpnBlockEnabled: vpnBlockEnabled ?? this.vpnBlockEnabled,
    );
  }

  Map<String, Object> toMap() {
    return {
      'packageName': packageName,
      'alwaysBlocked': alwaysBlocked,
      'dailyLimitMinutes': dailyLimitMinutes,
      'scheduleEnabled': scheduleEnabled,
      'scheduleStartMinute': scheduleStartMinute,
      'scheduleEndMinute': scheduleEndMinute,
      'allowedDays': allowedDays.toList(growable: false),
      'vpnBlockEnabled': vpnBlockEnabled,
    };
  }

  static AppControlRule fromMap(Map<Object?, Object?> map) {
    final rawDays = map['allowedDays'];
    final parsedDays = rawDays is List
        ? rawDays
              .whereType<num>()
              .map((day) => day.toInt())
              .where((day) => day >= 1 && day <= 7)
              .toSet()
        : <int>{};
    return AppControlRule(
      packageName: (map['packageName'] as String? ?? '').trim().toLowerCase(),
      alwaysBlocked: map['alwaysBlocked'] as bool? ?? false,
      dailyLimitMinutes: (map['dailyLimitMinutes'] as num?)?.toInt() ?? 0,
      scheduleEnabled: map['scheduleEnabled'] as bool? ?? false,
      scheduleStartMinute: (map['scheduleStartMinute'] as num?)?.toInt() ?? 0,
      scheduleEndMinute: (map['scheduleEndMinute'] as num?)?.toInt() ?? 1439,
      allowedDays: parsedDays.isEmpty
          ? const {1, 2, 3, 4, 5, 6, 7}
          : parsedDays,
      vpnBlockEnabled: map['vpnBlockEnabled'] as bool? ?? true,
    );
  }
}
