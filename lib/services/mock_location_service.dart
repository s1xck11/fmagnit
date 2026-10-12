import 'package:flutter/services.dart';

class MockLocationService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/mock_location');

  /// Проверяет, выбрано ли наше приложение как Mock Location app
  /// в настройках разработчика.
  Future<bool> isMockLocationEnabled() async {
    final result = await _channel.invokeMethod<bool>('isMockLocationEnabled');
    return result ?? false;
  }

  /// Открывает экран "Выбор приложения для подмены геолокации".
  Future<void> openDeveloperSettings() async {
    await _channel.invokeMethod('openDeveloperSettings');
  }

  /// Запускает mock-провайдер с указанными координатами.
  Future<bool> startMock({
    required double latitude,
    required double longitude,
    double accuracy = 3.0,
    double altitude = 0.0,
    int intervalMs = 1000,
  }) async {
    final result = await _channel.invokeMethod<bool>('startMock', {
      'latitude': latitude,
      'longitude': longitude,
      'accuracy': accuracy,
      'altitude': altitude,
      'intervalMs': intervalMs,
    });
    return result ?? false;
  }

  /// Обновляет координаты у уже запущенного провайдера.
  Future<bool> updateMock({
    required double latitude,
    required double longitude,
    double accuracy = 3.0,
    double altitude = 0.0,
  }) async {
    final result = await _channel.invokeMethod<bool>('updateMock', {
      'latitude': latitude,
      'longitude': longitude,
      'accuracy': accuracy,
      'altitude': altitude,
    });
    return result ?? false;
  }

  /// Останавливает mock и удаляет провайдер.
  Future<bool> stopMock() async {
    final result = await _channel.invokeMethod<bool>('stopMock');
    return result ?? false;
  }
}