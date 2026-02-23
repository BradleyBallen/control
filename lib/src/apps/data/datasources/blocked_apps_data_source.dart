import 'dart:async';

import 'package:flutter/services.dart';

abstract class BlockedAppsDataSource {
  Future<void> block(String packageName);

  Future<void> unblock(String packageName);

  Future<void> blockTemporarily(String packageName, Duration duration);

  Future<bool> isBlocked(String packageName);

  Future<Set<String>> getBlockedApps();

  Future<void> setBlockedApps(Set<String> packageNames);

  Future<bool> isAccessibilityServiceEnabled();

  Future<bool> requestVpnPermission();

  Future<void> startVpnBlocking();

  Future<void> stopVpnBlocking();

  Future<bool> isVpnBlockingActive();

  Future<void> syncProtection();
}

class MethodChannelBlockedAppsDataSource implements BlockedAppsDataSource {
  static const MethodChannel _channel = MethodChannel(
    'com.evolcorp.control_parental/blocked_apps',
  );
  final Map<String, Timer> _unblockTimers = <String, Timer>{};

  @override
  Future<void> block(String packageName) {
    return _channel.invokeMethod<void>('blockApp', packageName);
  }

  @override
  Future<void> unblock(String packageName) {
    return _channel.invokeMethod<void>('unblockApp', packageName);
  }

  @override
  Future<void> blockTemporarily(String packageName, Duration duration) async {
    await _channel.invokeMethod<void>('blockApp', packageName);

    _unblockTimers.remove(packageName)?.cancel();
    _unblockTimers[packageName] = Timer(duration, () async {
      _unblockTimers.remove(packageName);
      try {
        await _channel.invokeMethod<void>('unblockApp', packageName);
      } catch (_) {
        // The app can refresh blocked apps later if native channel call fails.
      }
    });
  }

  @override
  Future<bool> isBlocked(String packageName) async {
    final result = await _channel.invokeMethod<bool>('isBlocked', packageName);
    return result ?? false;
  }

  @override
  Future<Set<String>> getBlockedApps() async {
    final result = await _channel.invokeListMethod<String>('getBlockedApps');
    return (result ?? const <String>[]).toSet();
  }

  @override
  Future<void> setBlockedApps(Set<String> packageNames) {
    return _channel.invokeMethod<void>('setBlockedApps', {
      'packageNames': packageNames.toList(growable: false),
    });
  }

  @override
  Future<bool> isAccessibilityServiceEnabled() async {
    final result = await _channel.invokeMethod<bool>('isAccessibilityEnabled');
    return result ?? false;
  }

  @override
  Future<bool> requestVpnPermission() async {
    final result = await _channel.invokeMethod<bool>('requestVpnPermission');
    return result ?? false;
  }

  @override
  Future<void> startVpnBlocking() {
    return _channel.invokeMethod<void>('startVpnBlocking');
  }

  @override
  Future<void> stopVpnBlocking() {
    return _channel.invokeMethod<void>('stopVpnBlocking');
  }

  @override
  Future<bool> isVpnBlockingActive() async {
    final result = await _channel.invokeMethod<bool>('isVpnBlockingActive');
    return result ?? false;
  }

  @override
  Future<void> syncProtection() {
    return _channel.invokeMethod<void>('syncProtection');
  }
}
