import 'package:flutter/services.dart';

class OrderTargetInfo {
  final double? latitude;
  final double? longitude;
  final int timestamp; // epoch ms
  final String? sourceUri;

  OrderTargetInfo({
    required this.latitude,
    required this.longitude,
    required this.timestamp,
    required this.sourceUri,
  });

  DateTime get receivedAt =>
      DateTime.fromMillisecondsSinceEpoch(timestamp);

  bool get hasData => latitude != null && longitude != null && timestamp > 0;

  factory OrderTargetInfo.fromMap(Map<dynamic, dynamic> m) {
    return OrderTargetInfo(
      latitude: (m['latitude'] as num?)?.toDouble(),
      longitude: (m['longitude'] as num?)?.toDouble(),
      timestamp: (m['timestamp'] as num?)?.toInt() ?? 0,
      sourceUri: m['sourceUri'] as String?,
    );
  }
}

class CrashInfo {
  final String log;
  final int timestamp;
  final String? tag;

  CrashInfo({
    required this.log,
    required this.timestamp,
    required this.tag,
  });

  DateTime get when => DateTime.fromMillisecondsSinceEpoch(timestamp);

  factory CrashInfo.fromMap(Map<dynamic, dynamic> m) {
    return CrashInfo(
      log: (m['log'] as String?) ?? '',
      timestamp: (m['timestamp'] as num?)?.toInt() ?? 0,
      tag: m['tag'] as String?,
    );
  }
}

class OrderTargetService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/mock_location');

  Future<OrderTargetInfo?> getLast() async {
    final raw = await _channel.invokeMethod('getLastOrderTarget');
    if (raw == null) return null;
    return OrderTargetInfo.fromMap(raw as Map<dynamic, dynamic>);
  }

  Future<CrashInfo?> getLastCrash() async {
    final raw = await _channel.invokeMethod('getLastCrash');
    if (raw == null) return null;
    return CrashInfo.fromMap(raw as Map<dynamic, dynamic>);
  }

  Future<void> clearCrash() async {
    await _channel.invokeMethod('clearCrash');
  }
}