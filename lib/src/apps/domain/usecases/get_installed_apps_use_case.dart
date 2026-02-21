import '../entities/installed_app.dart';
import '../repositories/installed_apps_repository.dart';

class GetInstalledAppsUseCase {
  const GetInstalledAppsUseCase(this._repository);

  final InstalledAppsRepository _repository;

  Future<List<InstalledApp>> call({
    bool includeAppIcons = true,
    bool includeSystemApps = false,
  }) {
    return _repository.getInstalledApps(
      includeAppIcons: includeAppIcons,
      includeSystemApps: includeSystemApps,
    );
  }
}
