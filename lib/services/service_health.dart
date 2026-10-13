import '../services/mock_location_service.dart';
import '../services/overlay_service.dart';

class ServiceHealth {
  final bool overlayPermission;
  final bool usageAccess;
  final bool accessibility;
  final bool overlayRunning;
  final bool mockPermission;

  ServiceHealth({
    required this.overlayPermission,
    required this.usageAccess,
    required this.accessibility,
    required this.overlayRunning,
    required this.mockPermission,
  });

  bool get isAllOk =>
      overlayPermission &&
      usageAccess &&
      accessibility &&
      mockPermission;

  bool get isOverlayHealth => overlayPermission && usageAccess && accessibility;

  static Future<ServiceHealth> check() async {
    final overlay = OverlayService();
    final mock = MockLocationService();

    final ov = await overlay.hasOverlayPermission();
    final us = await overlay.hasUsageAccess();
    final ac = await overlay.hasAccessibilityPermission();
    final run = await overlay.isOverlayRunning();
    final mp = await mock.isMockLocationEnabled();

    return ServiceHealth(
      overlayPermission: ov,
      usageAccess: us,
      accessibility: ac,
      overlayRunning: run,
      mockPermission: mp,
    );
  }
}