import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../../apps/domain/entities/installed_app.dart';
import '../../domain/models/app_control_rule.dart';
import '../controllers/parental_dashboard_controller.dart';

class ParentalDashboardPage extends StatefulWidget {
  const ParentalDashboardPage({super.key, required this.controller});

  final ParentalDashboardController controller;

  @override
  State<ParentalDashboardPage> createState() => _ParentalDashboardPageState();
}

class _ParentalDashboardPageState extends State<ParentalDashboardPage> {
  int _tabIndex = 0;

  @override
  void initState() {
    super.initState();
    widget.controller.initialize();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final stage = widget.controller.authStage;
        if (stage == AuthStage.checking) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        return _buildUnlockedShell(context);
      },
    );
  }

  Widget _buildUnlockedShell(BuildContext context) {
    final tabs = <Widget>[
      _OverviewTab(controller: widget.controller),
      _AppsTab(
        controller: widget.controller,
        onEditRule: _openRuleEditor,
      ),
      _SchedulesTab(
        controller: widget.controller,
        onEditRule: _openRuleEditor,
      ),
      _ReportsTab(controller: widget.controller),
    ];
    final titles = const [
      'Mi enfoque',
      'Apps y pausas',
      'Limites',
      'Uso',
    ];

    return Scaffold(
      appBar: AppBar(
        title: Text(titles[_tabIndex]),
        actions: [
          IconButton(
            tooltip: 'Sincronizar',
            onPressed: widget.controller.loadAllData,
            icon: const Icon(Icons.sync),
          ),
        ],
      ),
      body: Stack(
        children: [
          tabs[_tabIndex],
          if (widget.controller.isLoading)
            const Align(
              alignment: Alignment.topCenter,
              child: LinearProgressIndicator(minHeight: 2),
            ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: (index) {
          setState(() {
            _tabIndex = index;
          });
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.dashboard_outlined),
            selectedIcon: Icon(Icons.dashboard),
            label: 'Inicio',
          ),
          NavigationDestination(
            icon: Icon(Icons.apps_outlined),
            selectedIcon: Icon(Icons.apps),
            label: 'Apps',
          ),
          NavigationDestination(
            icon: Icon(Icons.schedule_outlined),
            selectedIcon: Icon(Icons.schedule),
            label: 'Limites',
          ),
          NavigationDestination(
            icon: Icon(Icons.bar_chart_outlined),
            selectedIcon: Icon(Icons.bar_chart),
            label: 'Uso',
          ),
        ],
      ),
    );
  }

  Future<void> _openRuleEditor(
    InstalledApp app, {
    AppControlRule? initialRule,
  }) async {
    final currentRule = initialRule ?? widget.controller.ruleFor(app.packageName);
    final updatedRule = await showModalBottomSheet<AppControlRule>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) {
        return _RuleEditorSheet(
          app: app,
          initialRule:
              currentRule ?? AppControlRule(packageName: app.packageName),
        );
      },
    );

    if (!mounted || updatedRule == null) {
      return;
    }
    await widget.controller.saveRule(updatedRule);
  }
}

class _OverviewTab extends StatelessWidget {
  const _OverviewTab({required this.controller});

  final ParentalDashboardController controller;

  @override
  Widget build(BuildContext context) {
    final status = controller.protectionStatus;
    final settings = controller.securitySettings;

    return RefreshIndicator(
      onRefresh: controller.loadAllData,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Text(
            'Estado de autocontrol',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _StatusChip(
                label: 'Accesibilidad',
                active: status.accessibilityEnabled,
              ),
              _StatusChip(
                label: 'Uso de apps',
                active: status.usageAccessGranted,
              ),
              _StatusChip(
                label: 'Device Admin',
                active: status.deviceAdminEnabled,
              ),
              _StatusChip(
                label: 'Servicio persistente',
                active: status.protectionServiceRunning,
              ),
              _StatusChip(
                label: 'VPN local',
                active: status.vpnBlockingActive,
              ),
            ],
          ),
          const SizedBox(height: 16),
          Card(
            child: Column(
              children: [
                SwitchListTile(
                  value: settings.protectionEnabled,
                  title: const Text('Modo enfoque activo'),
                  subtitle: const Text('Aplica tus pausas, horarios y limites'),
                  onChanged: controller.setProtectionEnabled,
                ),
                SwitchListTile(
                  value: settings.blockSettingsPackages,
                  title: const Text('Proteger ajustes del sistema'),
                  subtitle: const Text('Evita desactivar protecciones facilmente'),
                  onChanged: (value) {
                    controller.applySecuritySettings(
                      settings.copyWith(blockSettingsPackages: value),
                    );
                  },
                ),
                SwitchListTile(
                  value: settings.protectUninstallFlow,
                  title: const Text('Proteger desinstalacion'),
                  subtitle: const Text('Bloquea instalador mientras el modo enfoque este activo'),
                  onChanged: (value) {
                    controller.applySecuritySettings(
                      settings.copyWith(protectUninstallFlow: value),
                    );
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: controller.openAccessibilitySettings,
                icon: const Icon(Icons.accessibility_new),
                label: const Text('Accesibilidad'),
              ),
              OutlinedButton.icon(
                onPressed: controller.openUsageAccessSettings,
                icon: const Icon(Icons.query_stats),
                label: const Text('Uso de apps'),
              ),
              OutlinedButton.icon(
                onPressed: controller.requestDeviceAdmin,
                icon: const Icon(Icons.admin_panel_settings),
                label: const Text('Device Admin'),
              ),
              OutlinedButton.icon(
                onPressed: controller.requestVpnPermissionAndStart,
                icon: const Icon(Icons.vpn_lock),
                label: const Text('Activar VPN'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: controller.syncProtection,
            icon: const Icon(Icons.sync),
            label: const Text('Sincronizar estado local'),
          ),
          if (controller.errorMessage != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                controller.errorMessage!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
    );
  }
}

class _AppsTab extends StatefulWidget {
  const _AppsTab({
    required this.controller,
    required this.onEditRule,
  });

  final ParentalDashboardController controller;
  final Future<void> Function(
    InstalledApp app, {
    AppControlRule? initialRule,
  })
  onEditRule;

  @override
  State<_AppsTab> createState() => _AppsTabState();
}

class _AppsTabState extends State<_AppsTab> {
  String _query = '';
  bool _isBlockingSocialApps = false;

  static const Set<String> _socialPackages = <String>{
    'com.facebook.katana',
    'com.facebook.lite',
    'com.instagram.android',
    'com.zhiliaoapp.musically',
    'com.snapchat.android',
    'com.twitter.android',
    'com.reddit.frontpage',
    'com.pinterest',
    'org.telegram.messenger',
    'com.whatsapp',
    'com.whatsapp.w4b',
    'com.viber.voip',
    'com.discord',
    'com.linkedin.android',
    'com.skype.raider',
    'com.tumblr',
    'com.bereal.ft',
    'com.ss.android.ugc.trill',
    'com.ss.android.ugc.aweme',
    'com.ss.android.ugc.trill.go',
    'com.microsoft.teams',
  };

  static const List<String> _socialPackageKeywords = <String>[
    'facebook',
    'instagram',
    'tiktok',
    'musically',
    'snapchat',
    'twitter',
    'reddit',
    'pinterest',
    'telegram',
    'whatsapp',
    'discord',
    'linkedin',
    'skype',
    'tumblr',
    'bereal',
    'threads',
    'messenger',
    'wechat',
    'line.',
    'signal',
  ];

  static const List<String> _socialNameKeywords = <String>[
    'facebook',
    'instagram',
    'tiktok',
    'snapchat',
    'x ',
    'twitter',
    'reddit',
    'pinterest',
    'telegram',
    'whatsapp',
    'discord',
    'linkedin',
    'skype',
    'tumblr',
    'bereal',
    'threads',
    'messenger',
    'wechat',
    'line',
    'signal',
  ];

  @override
  Widget build(BuildContext context) {
    final normalizedQuery = _query.trim().toLowerCase();
    final usageByPackage = widget.controller.usageMinutesTodayByPackage;
    final usagePermissionGranted = widget.controller.usageAccessGranted;
    final allSocialApps = widget.controller.apps
        .where(_isSocialApp)
        .toList(growable: false);
    final apps = widget.controller.apps.where((app) {
      if (normalizedQuery.isEmpty) {
        return true;
      }
      return app.appName.toLowerCase().contains(normalizedQuery) ||
          app.packageName.toLowerCase().contains(normalizedQuery);
    }).toList(growable: false);

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: TextField(
            decoration: const InputDecoration(
              prefixIcon: Icon(Icons.search),
              hintText: 'Buscar app por nombre o package',
            ),
            onChanged: (value) {
              setState(() {
                _query = value;
              });
            },
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                'Activa el switch para pausar una app ahora. Toca una app para definir limite diario u horario.',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: allSocialApps.isEmpty || _isBlockingSocialApps
                  ? null
                  : () => _blockAllSocialApps(allSocialApps),
              icon: _isBlockingSocialApps
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.group_off),
              label: Text(
                allSocialApps.isEmpty
                    ? 'No se detectaron redes sociales'
                    : 'Bloquear redes sociales (${allSocialApps.length})',
              ),
            ),
          ),
        ),
        if (widget.controller.errorMessage != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              widget.controller.errorMessage!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        Expanded(
          child: ListView.builder(
            itemCount: apps.length,
            itemBuilder: (context, index) {
              final app = apps[index];
              final packageName = app.packageName.toLowerCase();
              final alwaysBlocked = widget.controller.isAlwaysBlocked(packageName);
              final rule = widget.controller.ruleFor(packageName);
              final usageMinutes = usageByPackage[packageName] ?? 0;
              final usageSummary = usagePermissionGranted
                  ? 'Hoy: $usageMinutes min'
                  : 'Hoy: sin permiso de uso';
              return ListTile(
                leading: _InstalledAppIcon(iconBytes: app.iconBytes),
                title: Text(
                  app.appName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  '${app.packageName}\n$usageSummary | ${_ruleSummary(rule, alwaysBlocked: alwaysBlocked)}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                isThreeLine: true,
                trailing: Switch(
                  value: alwaysBlocked,
                  onChanged: (value) {
                    widget.controller.setAlwaysBlocked(packageName, value);
                  },
                ),
                onTap: () => widget.onEditRule(app, initialRule: rule),
              );
            },
          ),
        ),
      ],
    );
  }

  String _ruleSummary(AppControlRule? rule, {required bool alwaysBlocked}) {
    if (alwaysBlocked) {
      return 'Pausada manualmente';
    }
    if (rule == null) {
      return 'Sin limites activos';
    }
    final parts = <String>[];
    if (rule.scheduleEnabled) {
      parts.add(
        'Horario ${_formatMinute(rule.scheduleStartMinute)}-${_formatMinute(rule.scheduleEndMinute)}',
      );
    }
    if (rule.dailyLimitMinutes > 0) {
      parts.add('Limite ${rule.dailyLimitMinutes} min/dia');
    }
    if (parts.isEmpty) {
      return 'Regla guardada sin restricciones activas';
    }
      return parts.join(' | ');
  }

  bool _isSocialApp(InstalledApp app) {
    final packageName = app.packageName.toLowerCase();
    final appName = app.appName.toLowerCase();
    if (_socialPackages.contains(packageName)) {
      return true;
    }
    if (_socialPackageKeywords.any(packageName.contains)) {
      return true;
    }
    return _socialNameKeywords.any(appName.contains);
  }

  Future<void> _blockAllSocialApps(List<InstalledApp> socialApps) async {
    if (_isBlockingSocialApps) {
      return;
    }
    final socialPackages = socialApps
        .map((app) => app.packageName.toLowerCase())
        .toSet();
    if (socialPackages.isEmpty) {
      return;
    }

    setState(() {
      _isBlockingSocialApps = true;
    });
    final newlyBlocked = await widget.controller.setAlwaysBlockedForPackages(
      socialPackages,
      true,
    );
    if (!mounted) {
      return;
    }
    setState(() {
      _isBlockingSocialApps = false;
    });

    final message = newlyBlocked == 0
        ? 'Las redes sociales detectadas ya estaban pausadas.'
        : 'Se pausaron $newlyBlocked apps de redes sociales.';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }
}

class _InstalledAppIcon extends StatelessWidget {
  const _InstalledAppIcon({required this.iconBytes});

  final Uint8List? iconBytes;

  @override
  Widget build(BuildContext context) {
    if (iconBytes == null || iconBytes!.isEmpty) {
      return const CircleAvatar(
        child: Icon(Icons.apps),
      );
    }

    return CircleAvatar(
      backgroundImage: MemoryImage(iconBytes!),
    );
  }
}

class _SchedulesTab extends StatelessWidget {
  const _SchedulesTab({
    required this.controller,
    required this.onEditRule,
  });

  final ParentalDashboardController controller;
  final Future<void> Function(
    InstalledApp app, {
    AppControlRule? initialRule,
  })
  onEditRule;

  @override
  Widget build(BuildContext context) {
    final rules = controller.rulesByPackage.values.toList(growable: false)
      ..sort((a, b) => a.packageName.compareTo(b.packageName));
    if (rules.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'Aun no hay limites de horario o uso.\nConfiguralos desde la pestaña Apps.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return ListView.builder(
      itemCount: rules.length,
      itemBuilder: (context, index) {
        final rule = rules[index];
        final app = controller.apps.cast<InstalledApp?>().firstWhere(
          (candidate) => candidate?.packageName == rule.packageName,
          orElse: () => null,
        );
        final appName = app?.appName ?? rule.packageName;
        return ListTile(
          title: Text(appName),
          subtitle: Text(
            '${rule.packageName}\n${_buildRuleDescription(rule)}',
          ),
          isThreeLine: true,
          trailing: IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed: () => controller.removeRule(rule.packageName),
          ),
          onTap: () {
            if (app == null) {
              return;
            }
            onEditRule(app, initialRule: rule);
          },
        );
      },
    );
  }

  String _buildRuleDescription(AppControlRule rule) {
    final parts = <String>[];
    if (rule.scheduleEnabled) {
      final days = rule.allowedDays.toList(growable: false)..sort();
      parts.add(
        'Horario ${_formatMinute(rule.scheduleStartMinute)}-${_formatMinute(rule.scheduleEndMinute)} (${days.map(_dayShortLabel).join(',')})',
      );
    }
    if (rule.dailyLimitMinutes > 0) {
      parts.add('Limite diario ${rule.dailyLimitMinutes} min');
    }
    if (rule.vpnBlockEnabled) {
      parts.add('Bloqueo de internet por VPN activo');
    }
    if (parts.isEmpty) {
      return 'Regla sin condiciones activas';
    }
    return parts.join(' | ');
  }
}

class _ReportsTab extends StatelessWidget {
  const _ReportsTab({required this.controller});

  final ParentalDashboardController controller;

  @override
  Widget build(BuildContext context) {
    final usage = controller.usageReport;
    final events = controller.securityEvents;
    final appNamesByPackage = <String, String>{
      for (final app in controller.apps) app.packageName.toLowerCase(): app.appName,
    };
    return RefreshIndicator(
      onRefresh: () => controller.refreshReports(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Uso por app (${controller.reportDays} dias)',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              PopupMenuButton<int>(
                onSelected: (days) => controller.refreshReports(days: days),
                itemBuilder: (context) => const [
                  PopupMenuItem(value: 1, child: Text('1 dia')),
                  PopupMenuItem(value: 3, child: Text('3 dias')),
                  PopupMenuItem(value: 7, child: Text('7 dias')),
                  PopupMenuItem(value: 14, child: Text('14 dias')),
                ],
                child: const Padding(
                  padding: EdgeInsets.all(8),
                  child: Icon(Icons.filter_alt),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (usage.isEmpty)
            const ListTile(
              title: Text('Sin datos de uso'),
              subtitle: Text('Otorga permiso de uso para ver reportes detallados.'),
            )
          else
            ...usage.take(40).map(
              (entry) {
                final appName = appNamesByPackage[entry.packageName.toLowerCase()] ??
                    entry.packageName;
                return ListTile(
                  dense: true,
                  title: Text(appName),
                  subtitle: Text('${entry.packageName} | ${entry.date}'),
                  trailing: Text('${entry.usageMinutes} min'),
                );
              },
            ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: Text(
                  'Eventos de bloqueo',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              TextButton(
                onPressed: controller.clearSecurityEvents,
                child: const Text('Limpiar'),
              ),
            ],
          ),
          if (events.isEmpty)
            const ListTile(
              title: Text('Sin eventos'),
              subtitle: Text('Aun no hay bloqueos o alertas registradas.'),
            )
          else
            ...events.take(50).map(
              (event) => ListTile(
                dense: true,
                title: Text(event.type),
                subtitle: Text(
                  '${event.packageName ?? '-'} | ${event.reason ?? '-'}\n${event.timestamp}',
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _RuleEditorSheet extends StatefulWidget {
  const _RuleEditorSheet({
    required this.app,
    required this.initialRule,
  });

  final InstalledApp app;
  final AppControlRule initialRule;

  @override
  State<_RuleEditorSheet> createState() => _RuleEditorSheetState();
}

class _RuleEditorSheetState extends State<_RuleEditorSheet> {
  late bool _scheduleEnabled;
  late int _dailyLimitMinutes;
  late int _startMinute;
  late int _endMinute;
  late Set<int> _allowedDays;
  late bool _vpnBlockEnabled;

  @override
  void initState() {
    super.initState();
    _scheduleEnabled = widget.initialRule.scheduleEnabled;
    _dailyLimitMinutes = widget.initialRule.dailyLimitMinutes;
    _startMinute = widget.initialRule.scheduleStartMinute;
    _endMinute = widget.initialRule.scheduleEndMinute;
    _allowedDays = Set<int>.from(widget.initialRule.allowedDays);
    _vpnBlockEnabled = widget.initialRule.vpnBlockEnabled;
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 8, 16, bottomInset + 20),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.app.appName, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(
              widget.app.packageName,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            SwitchListTile(
              value: _scheduleEnabled,
              title: const Text('Limitar por horario'),
              subtitle: const Text('Bloquear fuera de la ventana permitida'),
              onChanged: (value) {
                setState(() {
                  _scheduleEnabled = value;
                });
              },
            ),
            if (_scheduleEnabled)
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  ActionChip(
                    label: Text('Desde ${_formatMinute(_startMinute)}'),
                    onPressed: () => _pickTime(
                      context,
                      initialMinute: _startMinute,
                      onSelected: (value) {
                        setState(() {
                          _startMinute = value;
                        });
                      },
                    ),
                  ),
                  ActionChip(
                    label: Text('Hasta ${_formatMinute(_endMinute)}'),
                    onPressed: () => _pickTime(
                      context,
                      initialMinute: _endMinute,
                      onSelected: (value) {
                        setState(() {
                          _endMinute = value;
                        });
                      },
                    ),
                  ),
                ],
              ),
            if (_scheduleEnabled) ...[
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                children: List.generate(7, (index) {
                  final day = index + 1;
                  final selected = _allowedDays.contains(day);
                  return FilterChip(
                    selected: selected,
                    label: Text(_dayShortLabel(day)),
                    onSelected: (value) {
                      setState(() {
                        if (value) {
                          _allowedDays.add(day);
                        } else if (_allowedDays.length > 1) {
                          _allowedDays.remove(day);
                        }
                      });
                    },
                  );
                }),
              ),
            ],
            const SizedBox(height: 12),
            TextFormField(
              initialValue: _dailyLimitMinutes == 0
                  ? ''
                  : _dailyLimitMinutes.toString(),
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Limite diario en minutos (0 = sin limite)',
              ),
              onChanged: (value) {
                setState(() {
                  _dailyLimitMinutes = int.tryParse(value) ?? 0;
                });
              },
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ChoiceChip(
                  selected: _dailyLimitMinutes == 0,
                  label: const Text('Sin limite'),
                  onSelected: (_) {
                    setState(() {
                      _dailyLimitMinutes = 0;
                    });
                  },
                ),
                for (final minutes in const [15, 30, 45, 60, 90, 120])
                  ChoiceChip(
                    selected: _dailyLimitMinutes == minutes,
                    label: Text('$minutes min'),
                    onSelected: (_) {
                      setState(() {
                        _dailyLimitMinutes = minutes;
                      });
                    },
                  ),
              ],
            ),
            SwitchListTile(
              value: _vpnBlockEnabled,
              title: const Text('Bloqueo de internet por VPN'),
              subtitle: const Text('Cortar trafico cuando la app este bloqueada'),
              onChanged: (value) {
                setState(() {
                  _vpnBlockEnabled = value;
                });
              },
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Cancelar'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.of(context).pop(
                        widget.initialRule.copyWith(
                          packageName: widget.app.packageName,
                          alwaysBlocked: false,
                          scheduleEnabled: _scheduleEnabled,
                          scheduleStartMinute: _startMinute,
                          scheduleEndMinute: _endMinute,
                          allowedDays: _allowedDays,
                          dailyLimitMinutes: _dailyLimitMinutes.clamp(0, 1440),
                          vpnBlockEnabled: _vpnBlockEnabled,
                        ),
                      );
                    },
                    child: const Text('Guardar limite'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickTime(
    BuildContext context, {
    required int initialMinute,
    required ValueChanged<int> onSelected,
  }) async {
    final initialTime = TimeOfDay(
      hour: initialMinute ~/ 60,
      minute: initialMinute % 60,
    );
    final selected = await showTimePicker(
      context: context,
      initialTime: initialTime,
    );
    if (selected == null) {
      return;
    }
    onSelected(selected.hour * 60 + selected.minute);
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label, required this.active});

  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final color = active ? Colors.green : Colors.orange;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: color.withOpacity(0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            active ? Icons.check_circle : Icons.warning_amber_rounded,
            size: 16,
            color: color,
          ),
          const SizedBox(width: 6),
          Text(
            '$label ${active ? "OK" : "Falta"}',
            style: TextStyle(color: color, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

String _formatMinute(int minute) {
  final safeMinute = minute.clamp(0, 1439);
  final hour = safeMinute ~/ 60;
  final min = safeMinute % 60;
  final hh = hour.toString().padLeft(2, '0');
  final mm = min.toString().padLeft(2, '0');
  return '$hh:$mm';
}

String _dayShortLabel(int day) {
  switch (day) {
    case 1:
      return 'L';
    case 2:
      return 'M';
    case 3:
      return 'X';
    case 4:
      return 'J';
    case 5:
      return 'V';
    case 6:
      return 'S';
    case 7:
      return 'D';
    default:
      return '?';
  }
}
