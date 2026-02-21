import 'dart:typed_data';

class InstalledApp {
  const InstalledApp({
    required this.appName,
    required this.packageName,
    this.iconBytes,
  });

  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
}
