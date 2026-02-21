class UsageReportEntry {
  const UsageReportEntry({
    required this.date,
    required this.dayStartMs,
    required this.packageName,
    required this.usageMs,
    required this.usageMinutes,
  });

  final String date;
  final int dayStartMs;
  final String packageName;
  final int usageMs;
  final int usageMinutes;

  static UsageReportEntry fromMap(Map<Object?, Object?> map) {
    return UsageReportEntry(
      date: map['date'] as String? ?? '',
      dayStartMs: (map['dayStartMs'] as num?)?.toInt() ?? 0,
      packageName: map['packageName'] as String? ?? '',
      usageMs: (map['usageMs'] as num?)?.toInt() ?? 0,
      usageMinutes: (map['usageMinutes'] as num?)?.toInt() ?? 0,
    );
  }
}
