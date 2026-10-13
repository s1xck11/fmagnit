import 'package:flutter/services.dart';

/// Dart-обёртка над нативным OverlayService + AccessibilityService.
class OverlayService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/overlay');

  /// Есть ли разрешение "Поверх других окон".
  Future<bool> hasOverlayPermission() async {
    final r = await _channel.invokeMethod<bool>('hasOverlayPermission');
    return r ?? false;
  }

  /// Открыть системные настройки для выдачи overlay-разрешения.
  Future<void> requestOverlayPermission() async {
    await _channel.invokeMethod('requestOverlayPermission');
  }

  /// Есть ли разрешение на AccessibilityService.
  Future<bool> hasAccessibilityPermission() async {
    final r = await _channel.invokeMethod<bool>('hasAccessibilityPermission');
    return r ?? false;
  }

  /// Открыть настройки Accessibility.
  Future<void> openAccessibilitySettings() async {
    await _channel.invokeMethod('openAccessibilitySettings');
  }

  /// Запустить сервис оверлея.
  Future<bool> startOverlay() async {
    final r = await _channel.invokeMethod<bool>('startOverlay');
    return r ?? false;
  }

  /// Остановить сервис оверлея.
  Future<bool> stopOverlay() async {
    final r = await _channel.invokeMethod<bool>('stopOverlay');
    return r ?? false;
  }

  /// Работает ли сервис сейчас.
  Future<bool> isOverlayRunning() async {
    final r = await _channel.invokeMethod<bool>('isOverlayRunning');
    return r ?? false;
  }

  /// Передать список точек оверлею.
  /// [pointsJson] — JSON-массив объектов с полями id, name.
  Future<void> updatePoints({
    required String pointsJson,
    String? activeId,
  }) async {
    await _channel.invokeMethod('updatePoints', {
      'pointsJson': pointsJson,
      'activeId': activeId,
    });
  }

  /// Событие от оверлея: пользователь выбрал точку и нажал "старт".
  /// [id] — id точки. Подписываемся через [onStartRequested].
  void setStartHandler(Future<void> Function(String id) handler) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onOverlayStart') {
        final args = call.arguments as Map<dynamic, dynamic>?;
        final id = args?['id'] as String?;
        if (id != null) await handler(id);
      } else if (call.method == 'onOverlayStop') {
        // Можно обработать отдельно, если надо.
      }
      return null;
    });
  }
}