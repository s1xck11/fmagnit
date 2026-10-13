import 'package:flutter/services.dart';

class RealLocation {
  final double latitude;
  final double longitude;
  final double accuracy;
  final int ageMs;

  RealLocation({
    required this.latitude,
    required this.longitude,
    required this.accuracy,
    required this.ageMs,
  });

  factory RealLocation.fromMap(Map<dynamic, dynamic> m) {
    return RealLocation(
      latitude: (m['latitude'] as num).toDouble(),
      longitude: (m['longitude'] as num).toDouble(),
      accuracy: (m['accuracy'] as num).toDouble(),
      ageMs: (m['ageMs'] as num).toInt(),
    );
  }

  Duration get age => Duration(milliseconds: ageMs);
}

class MockLocationService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/mock_location');

  Future<bool> isMockLocationEnabled() async {
    final result = await _channel.invokeMethod<bool>('isMockLocationEnabled');
    return result ?? false;
  }

  Future<void> openDeveloperSettings() async {
    await _channel.invokeMethod('openDeveloperSettings');
  }

  Future<bool> isMockRunning() async {
    final r = await _channel.invokeMethod<bool>('isMockRunning');
    return r ?? false;
  }

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

  Future<bool> stopMock() async {
    final result = await _channel.invokeMethod<bool>('stopMock');
    return result ?? false;
  }

  /// Жёсткий сброс mock. Вызывать:
  /// - при старте приложения,
  /// - по кнопке "Восстановить GPS",
  /// - при проблемах с залипшей координатой.
  Future<bool> forceResetMock() async {
    final result = await _channel.invokeMethod<bool>('forceResetMock');
    return result ?? false;
  }

  Future<RealLocation?> getRealLocation() async {
    final raw = await _channel.invokeMethod('getRealLocation');
    if (raw == null) return null;
    return RealLocation.fromMap(raw as Map<dynamic, dynamic>);
  }
}