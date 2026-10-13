import 'package:flutter/services.dart';

class OrderTarget {
  final double latitude;
  final double longitude;

  OrderTarget({required this.latitude, required this.longitude});
}

class OrderTargetService {
  static const MethodChannel _channel =
      MethodChannel('com.s1xck.fmagnit/overlay');

  /// Очистить координаты заказа, полученные из приложения доставки.
  Future<void> clearOrderTarget() async {
    await _channel.invokeMethod('clearOrderTarget');
  }
}