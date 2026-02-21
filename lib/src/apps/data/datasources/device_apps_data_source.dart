import 'package:flutter_device_apps/flutter_device_apps.dart';

abstract class DeviceAppsDataSource {
  Future<List<AppInfo>> getInstalledApps({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  });
}

class FlutterDeviceAppsDataSource implements DeviceAppsDataSource {
  @override
  Future<List<AppInfo>> getInstalledApps({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  }) {
    return FlutterDeviceApps.listApps(
      includeIcons: includeAppIcons,
      includeSystem: includeSystemApps,
      onlyLaunchable: true,
    );
  }
}
