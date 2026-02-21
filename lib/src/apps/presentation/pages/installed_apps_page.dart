import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../domain/entities/installed_app.dart';
import '../controllers/installed_apps_controller.dart';

class InstalledAppsPage extends StatefulWidget {
  const InstalledAppsPage({super.key, required this.controller});

  final InstalledAppsController controller;

  @override
  State<InstalledAppsPage> createState() => _InstalledAppsPageState();
}

class _InstalledAppsPageState extends State<InstalledAppsPage> {
  bool _isApplyingBlock = false;
  bool _isApplyingUnblock = false;

  bool get _isApplyingAction =>
      _isApplyingBlock ||
      _isApplyingUnblock ||
      widget.controller.isApplyingProtection;

  @override
  void initState() {
    super.initState();
    widget.controller.loadApps();
  }

  Future<void> _applyBlockAccess() async {
    setState(() {
      _isApplyingBlock = true;
    });

    final blockedCount = widget.controller.selectedPackages
        .where((pkg) => !widget.controller.isBlocked(pkg))
        .length;
    final success = await widget.controller.applySelectedBlocks();
    String? protectionMessage;
    if (success) {
      protectionMessage = await widget.controller.enforceProtection();
    }
    if (!mounted) {
      return;
    }

    setState(() {
      _isApplyingBlock = false;
    });

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          success
              ? blockedCount == 0
                    ? 'Las apps seleccionadas ya estaban bloqueadas.'
                    : 'Acceso bloqueado para $blockedCount apps.'
              : 'No se pudo bloquear el acceso.',
        ),
      ),
    );
    if (protectionMessage != null && protectionMessage.isNotEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(protectionMessage)),
      );
    }
  }

  Future<void> _applyUnblockAccess() async {
    setState(() {
      _isApplyingUnblock = true;
    });

    final unblockedCount = widget.controller.selectedPackages
        .where(widget.controller.isBlocked)
        .length;
    final success = await widget.controller.applySelectedUnblocks();
    if (!mounted) {
      return;
    }

    setState(() {
      _isApplyingUnblock = false;
    });

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          success
              ? unblockedCount == 0
                    ? 'No habia apps bloqueadas en la seleccion.'
                    : 'Acceso desbloqueado para $unblockedCount apps.'
              : 'No se pudo desbloquear el acceso.',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('Apps instaladas'),
            actions: [
              IconButton(
                onPressed: widget.controller.loadApps,
                icon: const Icon(Icons.refresh),
                tooltip: 'Recargar',
              ),
            ],
          ),
          body: _buildContent(context),
        );
      },
    );
  }

  Widget _buildContent(BuildContext context) {
    switch (widget.controller.status) {
      case InstalledAppsStatus.initial:
      case InstalledAppsStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case InstalledAppsStatus.error:
        return _ErrorState(
          message: widget.controller.errorMessage ?? 'Error inesperado.',
          onRetry: widget.controller.loadApps,
        );
      case InstalledAppsStatus.loaded:
        return _buildLoadedState(context);
    }
  }

  Widget _buildLoadedState(BuildContext context) {
    final apps = widget.controller.apps;
    final hasSelection = widget.controller.selectedPackages.isNotEmpty;
    final hasBlockedSelection = widget.controller.selectedPackages.any(
      widget.controller.isBlocked,
    );
    final hasUnblockedSelection = widget.controller.selectedPackages.any(
      (pkg) => !widget.controller.isBlocked(pkg),
    );
    if (apps.isEmpty) {
      return const Center(child: Text('No se encontraron apps instaladas.'));
    }

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Seleccionadas: ${widget.controller.selectedPackages.length}',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        Text(
                          'Bloqueadas: ${widget.controller.blockedPackages.length}',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ],
                    ),
                  ),
                  if (widget.controller.selectedPackages.isNotEmpty)
                    TextButton(
                      onPressed: widget.controller.clearSelection,
                      child: const Text('Limpiar'),
                    ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _ProtectionChip(
                    label: widget.controller.isAccessibilityEnabled
                        ? 'Accesibilidad activa'
                        : 'Accesibilidad inactiva',
                    color: widget.controller.isAccessibilityEnabled
                        ? Colors.green
                        : Colors.orange,
                    icon: widget.controller.isAccessibilityEnabled
                        ? Icons.accessibility_new
                        : Icons.warning_amber_rounded,
                  ),
                  _ProtectionChip(
                    label: widget.controller.isVpnBlockingActive
                        ? 'VPN de bloqueo activa'
                        : 'VPN de bloqueo inactiva',
                    color: widget.controller.isVpnBlockingActive
                        ? Colors.green
                        : Colors.blueGrey,
                    icon: widget.controller.isVpnBlockingActive
                        ? Icons.vpn_lock
                        : Icons.vpn_key_off,
                  ),
                ],
              ),
              if (widget.controller.isApplyingProtection)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: LinearProgressIndicator(minHeight: 2),
                ),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: apps.length,
            itemBuilder: (context, index) {
              return _AppTile(
                app: apps[index],
                selected: widget.controller.isSelected(apps[index].packageName),
                blocked: widget.controller.isBlocked(apps[index].packageName),
                onToggle: () => widget.controller.toggleSelection(
                  apps[index].packageName,
                ),
              );
            },
          ),
        ),
        SafeArea(
          top: false,
          minimum: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Row(
            children: [
              Expanded(
                child: ElevatedButton.icon(
                  onPressed:
                      !hasSelection || !hasUnblockedSelection || _isApplyingAction
                      ? null
                      : _applyBlockAccess,
                  icon: _isApplyingBlock
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.lock),
                  label: const Text('Bloquear acceso'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed:
                      !hasSelection || !hasBlockedSelection || _isApplyingAction
                      ? null
                      : _applyUnblockAccess,
                  icon: _isApplyingUnblock
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.lock_open),
                  label: const Text('Desbloquear acceso'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _AppTile extends StatelessWidget {
  const _AppTile({
    required this.app,
    required this.selected,
    required this.blocked,
    required this.onToggle,
  });

  final InstalledApp app;
  final bool selected;
  final bool blocked;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: _AppIcon(iconBytes: app.iconBytes),
      title: Text(
        app.appName,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        app.packageName,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (blocked)
            const Padding(
              padding: EdgeInsets.only(right: 8),
              child: Icon(Icons.lock, size: 18),
            ),
          Checkbox(
            value: selected,
            onChanged: (_) => onToggle(),
          ),
        ],
      ),
      onTap: onToggle,
    );
  }
}

class _AppIcon extends StatelessWidget {
  const _AppIcon({required this.iconBytes});

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

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: onRetry,
              child: const Text('Reintentar'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProtectionChip extends StatelessWidget {
  const _ProtectionChip({
    required this.label,
    required this.color,
    required this.icon,
  });

  final String label;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withOpacity(0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(
            label,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
