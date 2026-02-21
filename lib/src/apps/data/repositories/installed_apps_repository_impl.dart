import '../../domain/entities/installed_app.dart';
import '../../domain/repositories/installed_apps_repository.dart';
import '../datasources/device_apps_data_source.dart';

class InstalledAppsRepositoryImpl implements InstalledAppsRepository {
  InstalledAppsRepositoryImpl(this._dataSource);

  static const String _selfPackageName = 'com.evolcorp.control_parental';

  final DeviceAppsDataSource _dataSource;

  @override
  Future<List<InstalledApp>> getInstalledApps({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  }) async {
    final apps = await _dataSource.getInstalledApps(
      includeAppIcons: includeAppIcons,
      includeSystemApps: includeSystemApps,
    );

    final installedApps = apps
        .where((app) {
          final packageName = app.packageName?.trim() ?? '';
          if (packageName.isEmpty) {
            return false;
          }
          return packageName.toLowerCase() != _selfPackageName;
        })
        .map(
          (app) => InstalledApp(
            appName: _resolveAppName(app.appName, app.packageName!),
            packageName: app.packageName!,
            iconBytes: app.iconBytes,
          ),
        )
        .toList();

    installedApps.sort(
      (a, b) => a.appName.toLowerCase().compareTo(b.appName.toLowerCase()),
    );

    return installedApps;
  }

  String _resolveAppName(String? appName, String packageName) {
    final normalizedName = appName?.trim();
    if (normalizedName == null || normalizedName.isEmpty) {
      return packageName;
    }
    return normalizedName;
  }
}
