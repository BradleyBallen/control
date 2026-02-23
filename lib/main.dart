import 'dart:async';

import 'package:flutter/material.dart';

import 'src/apps/data/datasources/device_apps_data_source.dart';
import 'src/apps/data/repositories/installed_apps_repository_impl.dart';
import 'src/apps/domain/usecases/get_installed_apps_use_case.dart';
import 'src/protection/data/native_parental_data_source.dart';
import 'src/protection/presentation/controllers/parental_dashboard_controller.dart';
import 'src/protection/presentation/pages/parental_dashboard_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    debugPrint(details.exceptionAsString());
    debugPrintStack(stackTrace: details.stack);
  };

  runZonedGuarded(
    () => runApp(const MyApp()),
    (error, stackTrace) {
      debugPrint('Unhandled app error: $error');
      debugPrintStack(stackTrace: stackTrace);
    },
  );
}

ParentalDashboardController _buildController() {
  final installedAppsDataSource = FlutterDeviceAppsDataSource();
  final installedAppsRepository = InstalledAppsRepositoryImpl(
    installedAppsDataSource,
  );
  final getInstalledAppsUseCase = GetInstalledAppsUseCase(
    installedAppsRepository,
  );
  final nativeDataSource = NativeParentalDataSource();
  return ParentalDashboardController(getInstalledAppsUseCase, nativeDataSource);
}

class MyApp extends StatefulWidget {
  const MyApp({super.key, ParentalDashboardController? controller})
    : _externalController = controller;

  final ParentalDashboardController? _externalController;

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final ParentalDashboardController _controller;
  late final bool _ownsController;

  @override
  void initState() {
    super.initState();
    _ownsController = widget._externalController == null;
    _controller = widget._externalController ?? _buildController();
  }

  @override
  void dispose() {
    if (_ownsController) {
      _controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Control de Uso',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: ParentalDashboardPage(controller: _controller),
    );
  }
}
