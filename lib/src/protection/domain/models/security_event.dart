class SecurityEvent {
  const SecurityEvent({
    required this.timestampMs,
    required this.type,
    this.packageName,
    this.reason,
    this.details,
  });

  final int timestampMs;
  final String type;
  final String? packageName;
  final String? reason;
  final String? details;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  static SecurityEvent fromMap(Map<Object?, Object?> map) {
    return SecurityEvent(
      timestampMs: (map['timestampMs'] as num?)?.toInt() ?? 0,
      type: (map['type'] as String? ?? '').trim(),
      packageName: map['packageName'] as String?,
      reason: map['reason'] as String?,
      details: map['details'] as String?,
    );
  }
}
