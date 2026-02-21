import '../entities/installed_app.dart';

abstract class InstalledAppsRepository {
  Future<List<InstalledApp>> getInstalledApps({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  });
}
