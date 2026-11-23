import 'package:flutter/services.dart';

class OverlayService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/overlay');
  static const EventChannel _eventChannel =
      EventChannel('com.s1xck.fmagnit/overlay_events');

  // Поток для реактивного получения состояния оверлея
  Stream<Map<dynamic, dynamic>?> get overlayStateStream {
    return _eventChannel.receiveBroadcastStream().cast<Map<dynamic, dynamic>?>();
  }

  Future<bool> hasOverlayPermission() async {
    final r = await _channel.invokeMethod<bool>('hasOverlayPermission');
    return r ?? false;
  }

  Future<void> requestOverlayPermission() async {
    await _channel.invokeMethod('requestOverlayPermission');
  }

  Future<bool> hasAccessibilityPermission() async {
    final r = await _channel.invokeMethod<bool>('hasAccessibilityPermission');
    return r ?? false;
  }

  Future<void> openAccessibilitySettings() async {
    await _channel.invokeMethod('openAccessibilitySettings');
  }

  Future<bool> hasUsageAccess() async {
    final r = await _channel.invokeMethod<bool>('hasUsageAccess');
    return r ?? false;
  }

  Future<void> openUsageAccessSettings() async {
    await _channel.invokeMethod('openUsageAccessSettings');
  }

  Future<String?> getForegroundPackage() async {
    return await _channel.invokeMethod<String>('getForegroundPackage');
  }

  Future<bool> startOverlay() async {
    final r = await _channel.invokeMethod<bool>('startOverlay');
    return r ?? false;
  }

  Future<bool> stopOverlay() async {
    final r = await _channel.invokeMethod<bool>('stopOverlay');
    return r ?? false;
  }

  Future<bool> isOverlayRunning() async {
    final r = await _channel.invokeMethod<bool>('isOverlayRunning');
    return r ?? false;
  }

  Future<void> updatePoints({
    required String pointsJson,
    String? activeId,
  }) async {
    await _channel.invokeMethod('updatePoints', {
      'pointsJson': pointsJson,
      'activeId': activeId,
    });
  }

  Future<void> updateDoorCodes({required String codesJson}) async {
    await _channel.invokeMethod('updateDoorCodes', {'codesJson': codesJson});
  }

  Future<void> clearOrderTarget() async {
    await _channel.invokeMethod('clearOrderTarget');
  }

  Future<void> setManualOrderTarget({
    required double latitude,
    required double longitude,
  }) async {
    await _channel.invokeMethod('setManualOrderTarget', {
      'latitude': latitude,
      'longitude': longitude,
    });
  }

  Future<int> getHeaderWidthPct() async {
    final r = await _channel.invokeMethod<int>('getHeaderWidthPct');
    return r ?? 65;
  }

  Future<void> setHeaderWidthPct(int pct) async {
    await _channel.invokeMethod('setHeaderWidthPct', {'pct': pct});
  }

  Future<String> getTransportMode() async {
    final r = await _channel.invokeMethod<String>('getTransportMode');
    return r ?? 'auto';
  }

  Future<void> setTransportMode(String mode) async {
    await _channel.invokeMethod('setTransportMode', {'mode': mode});
  }

  Future<String> getMapProvider() async {
    final r = await _channel.invokeMethod<String>('getMapProvider');
    return r ?? 'auto';
  }

  Future<void> setMapProvider(String provider) async {
    await _channel.invokeMethod('setMapProvider', {'provider': provider});
  }

  Future<bool> isMapKitAvailable() async {
    final r = await _channel.invokeMethod<bool>('isMapKitAvailable');
    return r ?? false;
  }

  Future<bool> getTrafficEnabled() async {
    final r = await _channel.invokeMethod<bool>('getTrafficEnabled');
    return r ?? false;
  }

  Future<void> setTrafficEnabled(bool enabled) async {
    await _channel.invokeMethod('setTrafficEnabled', {'enabled': enabled});
  }

  /// Геокод Яндекса. null/пусто = недоступно -> вызывающий делает fallback на OSM.
  Future<List<Map<String, dynamic>>?> geocodeAddress(String query) async {
    try {
      final r = await _channel.invokeMethod<List<dynamic>>(
        'geocodeAddress',
        {'query': query},
      );
      if (r == null) return null;
      return r
          .cast<Map<dynamic, dynamic>>()
          .map((m) => Map<String, dynamic>.from(m))
          .toList();
    } catch (_) {
      return null;
    }
  }

  void setStartHandler(Future<void> Function(String id) handler) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onOverlayStart') {
        final args = call.arguments as Map<dynamic, dynamic>?;
        final id = args?['id'] as String?;
        if (id != null) await handler(id);
      }
      return null;
    });
  }
}