import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/services.dart';

class MapDownloadProgress {
  final bool running;
  final int downloaded;
  final int total;
  final bool finished;
  final String? error;
  final String? regionName;

  MapDownloadProgress({
    required this.running,
    required this.downloaded,
    required this.total,
    required this.finished,
    this.error,
    this.regionName,
  });

  double get fraction {
    if (total <= 0) return 0.0;
    return (downloaded / total).clamp(0.0, 1.0);
  }

  factory MapDownloadProgress.idle() => MapDownloadProgress(
        running: false,
        downloaded: 0,
        total: 0,
        finished: false,
      );

  factory MapDownloadProgress.fromMap(Map<dynamic, dynamic> m) {
    return MapDownloadProgress(
      running: (m['running'] as bool?) ?? false,
      downloaded: (m['downloaded'] as num?)?.toInt() ?? 0,
      total: (m['total'] as num?)?.toInt() ?? 0,
      finished: (m['finished'] as bool?) ?? false,
      error: m['error'] as String?,
      regionName: m['regionName'] as String?,
    );
  }
}

class DownloadedRegion {
  final String id;
  final String name;
  final double south;
  final double north;
  final double west;
  final double east;
  final int minZoom;
  final int maxZoom;
  final int tileCount;
  final int timestamp;

  DownloadedRegion({
    required this.id,
    required this.name,
    required this.south,
    required this.north,
    required this.west,
    required this.east,
    required this.minZoom,
    required this.maxZoom,
    required this.tileCount,
    required this.timestamp,
  });

  DateTime get downloadedAt => DateTime.fromMillisecondsSinceEpoch(timestamp);

  factory DownloadedRegion.fromMap(Map<dynamic, dynamic> m) {
    return DownloadedRegion(
      id: (m['id'] as String?) ?? '',
      name: (m['name'] as String?) ?? '',
      south: (m['south'] as num?)?.toDouble() ?? 0,
      north: (m['north'] as num?)?.toDouble() ?? 0,
      west: (m['west'] as num?)?.toDouble() ?? 0,
      east: (m['east'] as num?)?.toDouble() ?? 0,
      minZoom: (m['minZoom'] as num?)?.toInt() ?? 12,
      maxZoom: (m['maxZoom'] as num?)?.toInt() ?? 17,
      tileCount: (m['tileCount'] as num?)?.toInt() ?? 0,
      timestamp: (m['timestamp'] as num?)?.toInt() ?? 0,
    );
  }
}

class MapDownloadService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/mock_location');

  Future<void> startDownload({
    required String name,
    required double south,
    required double north,
    required double west,
    required double east,
    int minZoom = 12,
    int maxZoom = 17,
  }) async {
    await _channel.invokeMethod('startMapDownload', {
      'name': name,
      'south': south,
      'north': north,
      'west': west,
      'east': east,
      'minZoom': minZoom,
      'maxZoom': maxZoom,
    });
  }

  Future<MapDownloadProgress> getProgress() async {
    final raw = await _channel.invokeMethod('getMapDownloadProgress');
    if (raw == null) return MapDownloadProgress.idle();
    return MapDownloadProgress.fromMap(raw as Map<dynamic, dynamic>);
  }

  Future<void> cancelDownload() async {
    await _channel.invokeMethod('cancelMapDownload');
  }

  Future<List<DownloadedRegion>> getDownloadedRegions() async {
    final raw = await _channel.invokeMethod('getDownloadedRegions');
    if (raw == null) return [];
    final list = raw as List<dynamic>;
    return list
        .map((e) => DownloadedRegion.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  Future<void> deleteRegion(String id) async {
    await _channel.invokeMethod('deleteDownloadedRegion', {'id': id});
  }

  /// Оценка количества тайлов для предупреждения пользователя.
  static int estimateTiles({
    required double south,
    required double north,
    required double west,
    required double east,
    required int minZoom,
    required int maxZoom,
  }) {
    int total = 0;
    for (int z = minZoom; z <= maxZoom; z++) {
      final n = 1 << z;

      final x1 = ((west + 180.0) / 360.0 * n).floor();
      final x2 = ((east + 180.0) / 360.0 * n).floor();

      final latRad1 = north * math.pi / 180.0;
      final latRad2 = south * math.pi / 180.0;

      final y1 = ((1.0 -
                  math.log(
                        math.tan(latRad1) + 1.0 / math.cos(latRad1),
                      ) /
                      math.pi) /
              2.0 *
              n)
          .floor();
      final y2 = ((1.0 -
                  math.log(
                        math.tan(latRad2) + 1.0 / math.cos(latRad2),
                      ) /
                      math.pi) /
              2.0 *
              n)
          .floor();

      final dx = (x2 - x1).abs() + 1;
      final dy = (y2 - y1).abs() + 1;
      total += dx * dy;
    }
    return total;
  }
}