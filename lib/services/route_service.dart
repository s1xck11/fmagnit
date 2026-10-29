import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class RoutePoint {
  final double latitude;
  final double longitude;

  RoutePoint(this.latitude, this.longitude);

  factory RoutePoint.fromList(List<dynamic> list) {
    return RoutePoint(
      (list[0] as num).toDouble(),
      (list[1] as num).toDouble(),
    );
  }
}

class RouteResult {
  final List<RoutePoint> points;
  final double distanceMeters;
  final int timeSeconds;
  final String source; // "brouter" | "cache" | "straight"

  RouteResult({
    required this.points,
    required this.distanceMeters,
    required this.timeSeconds,
    required this.source,
  });

  factory RouteResult.fromMap(Map<dynamic, dynamic> map) {
    final pointsList = (map['points'] as List)
        .map((p) => RoutePoint.fromList(p as List))
        .toList();

    return RouteResult(
      points: pointsList,
      distanceMeters: (map['distanceMeters'] as num).toDouble(),
      timeSeconds: (map['timeSeconds'] as num).toInt(),
      source: map['source'] as String,
    );
  }

  String get formattedDistance {
    if (distanceMeters < 1000) {
      return '${distanceMeters.round()} м';
    } else {
      return '${(distanceMeters / 1000).toStringAsFixed(1)} км';
    }
  }

  String get formattedTime {
    final minutes = (timeSeconds / 60).round();
    if (minutes < 60) {
      return '$minutes мин';
    } else {
      final hours = minutes ~/ 60;
      final mins = minutes % 60;
      return '$hours ч $mins мин';
    }
  }
}

class RouteService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/route');

  Future<bool> hasMapsForLocation({
    required double latitude,
    required double longitude,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('hasMapsForLocation', {
        'latitude': latitude,
        'longitude': longitude,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('Ошибка hasMapsForLocation: $e');
      return false;
    }
  }

  Future<List<String>> getAvailableRegions() async {
    try {
      final result = await _channel.invokeMethod<List>('getAvailableRegions');
      return result?.cast<String>() ?? [];
    } catch (e) {
      debugPrint('Ошибка getAvailableRegions: $e');
      return [];
    }
  }

  Future<RouteResult> buildRoute({
    required double fromLat,
    required double fromLon,
    required double toLat,
    required double toLon,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map>('buildRoute', {
        'fromLat': fromLat,
        'fromLon': fromLon,
        'toLat': toLat,
        'toLon': toLon,
      });

      if (result == null) {
        throw Exception('Route result is null');
      }

      return RouteResult.fromMap(result);
    } catch (e) {
      debugPrint('Ошибка buildRoute: $e');
      rethrow;
    }
  }

  Future<void> clearCache() async {
    try {
      await _channel.invokeMethod('clearCache');
    } catch (e) {
      debugPrint('Ошибка clearCache: $e');
    }
  }
}
