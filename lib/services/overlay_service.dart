import 'package:flutter/services.dart';

class OverlayService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/overlay');

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

  /// Передаёт в оверлей список кодов домофонов (JSON-массив).
  /// Элемент: {id, code, address, latitude, longitude}
  Future<void> updateDoorCodes({required String codesJson}) async {
    await _channel.invokeMethod('updateDoorCodes', {
      'codesJson': codesJson,
    });
  }

  /// Очищает координаты заказа, полученные из приложения доставки.
  Future<void> clearOrderTarget() async {
    await _channel.invokeMethod('clearOrderTarget');
  }

  /// Тестовый заказ: устанавливает координаты заказа вручную
  /// (без перехвата из Магнита). Оверлей раскроется автоматически.
  Future<void> setManualOrderTarget({
    required double latitude,
    required double longitude,
  }) async {
    await _channel.invokeMethod('setManualOrderTarget', {
      'latitude': latitude,
      'longitude': longitude,
    });
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