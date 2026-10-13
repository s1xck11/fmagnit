import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../main.dart';
import '../models/waypoint.dart';
import '../services/mock_location_service.dart';
import '../services/overlay_service.dart';
import '../services/service_health.dart';
import '../storage/waypoint_storage.dart';
import 'address_search_dialog.dart';
import 'health_page.dart';
import 'import_export_dialogs.dart';
import 'mock_test_panel.dart';
import 'sort_menu.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _storage = WaypointStorage();
  final _overlay = OverlayService();
  final _mock = MockLocationService();

  List<Waypoint> _waypoints = [];
  bool _loading = true;

  bool _overlayRunning = false;
  bool _overlayPerm = false;
  bool _accessPerm = false;
  bool _usagePerm = false;
  String? _activeId;

  SortMode _sortMode = SortMode.byDate;
  final Set<String> _collapsedGroups = {};

  Timer? _stateTimer;

  @override
  void initState() {
    super.initState();
    _load();
    _refreshPermissions();
    _overlay.setStartHandler(_onOverlayStartRequested);

    // Автосброс mock — на случай, если остался от прошлой сессии
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _mock.forceResetMock();
    });

    _stateTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _pollOverlayState();
    });
  }

  @override
  void dispose() {
    _stateTimer?.cancel();
    super.dispose();
  }

  Future<void> _pollOverlayState() async {
    final running = await _overlay.isOverlayRunning();
    final ac = await _overlay.hasAccessibilityPermission();
    if (!mounted) return;
    if (running != _overlayRunning || ac != _accessPerm) {
      setState(() {
        _overlayRunning = running;
        _accessPerm = ac;
      });
    }
  }

  Future<void> _onOverlayStartRequested(String id) async {
    final w = _waypoints.firstWhere(
      (e) => e.id == id,
      orElse: () => _waypoints.first,
    );
    await _mock.startMock(
      latitude: w.latitude,
      longitude: w.longitude,
      accuracy: w.accuracy,
    );
  }

  Future<void> _load() async {
    final list = await _storage.loadAll();
    setState(() {
      _waypoints = list;
      _loading = false;
    });
    await _pushPointsToOverlay();
  }

  Future<void> _refreshPermissions() async {
    final running = await _overlay.isOverlayRunning();
    final ov = await _overlay.hasOverlayPermission();
    final ac = await _overlay.hasAccessibilityPermission();
    final us = await _overlay.hasUsageAccess();
    if (!mounted) return;
    setState(() {
      _overlayRunning = running;
      _overlayPerm = ov;
      _accessPerm = ac;
      _usagePerm = us;
    });
  }

  Future<void> _pushPointsToOverlay() async {
    final json = jsonEncode(_waypoints.map((w) => {
          'id': w.id,
          'name': w.name,
        }).toList());
    await _overlay.updatePoints(pointsJson: json, activeId: _activeId);
  }

  Future<void> _toggleOverlay() async {
    if (!_overlayPerm) {
      await _overlay.requestOverlayPermission();
      return;
    }
    if (!_usagePerm) {
      await _overlay.openUsageAccessSettings();
      return;
    }
    if (_overlayRunning) {
      await _overlay.stopOverlay();
    } else {
      await _overlay.startOverlay();
      await Future.delayed(const Duration(milliseconds: 400));
      await _pushPointsToOverlay();
    }
    await Future.delayed(const Duration(milliseconds: 600));
    await _refreshPermissions();
  }

  Future<void> _openHealth() async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const HealthPage()),
    );
    await _refreshPermissions();
  }

  Future<void> _restoreGps() async {
    final ok = await _mock.forceResetMock();
    if (!mounted) return;
    _snack(ok
        ? 'GPS восстановлен. Подожди 5-10 секунд, потом отмечай прибытие в Магните.'
        : 'Не удалось сбросить mock. Попробуй перезагрузить телефон.');
  }

  Future<void> _addWaypoint() async {
    final existingGroups = _waypoints
        .map((w) => w.group)
        .where((g) => g.isNotEmpty)
        .toSet()
        .toList();

    final result = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _WaypointDialog(groups: existingGroups),
    );
    if (result == null) return;
    await _storage.upsert(result);
    await _load();
  }

  Future<void> _addCurrentLocation() async {
    final mockRunning = await _mock.isMockRunning();
    if (mockRunning) {
      if (!mounted) return;
      await _showInfoDialog(
        'Mock активен',
        'Сейчас запущена подмена координат. Если сохранить точку сейчас, '
            'будет сохранена поддельная координата, а не реальная.\n\n'
            'Останови подмену и попробуй снова.',
      );
      return;
    }

    final real = await _mock.getRealLocation();
    if (!mounted) return;

    if (real == null) {
      _snack('Не удалось получить реальную локацию. '
          'Выйди на улицу или открой Google Maps, чтобы GPS прогрелся.');
      return;
    }

    final existingGroups = _waypoints
        .map((w) => w.group)
        .where((g) => g.isNotEmpty)
        .toSet()
        .toList();

    final result = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _WaypointDialog(
        groups: existingGroups,
        presetLat: real.latitude,
        presetLon: real.longitude,
        presetAcc: real.accuracy,
        infoLine: 'Точность ${real.accuracy.toStringAsFixed(0)} м, '
            'получено ${_humanAge(real.age)} назад',
      ),
    );
    if (result == null) return;
    await _storage.upsert(result);
    await _load();
  }

  Future<void> _searchByAddress() async {
    final result = await showAddressSearchDialog(context);
    if (result == null) return;
    if (!mounted) return;

    final existingGroups = _waypoints
        .map((w) => w.group)
        .where((g) => g.isNotEmpty)
        .toSet()
        .toList();

    final edited = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _WaypointDialog(
        initial: result,
        groups: existingGroups,
      ),
    );
    if (edited == null) return;
    await _storage.upsert(edited);
    await _load();
  }

  String _humanAge(Duration d) {
    if (d.inSeconds < 5) return 'только что';
    if (d.inSeconds < 60) return '${d.inSeconds} сек';
    if (d.inMinutes < 60) return '${d.inMinutes} мин';
    return '${d.inHours} ч';
  }

  Future<void> _editWaypoint(Waypoint w) async {
    final existingGroups = _waypoints
        .map((e) => e.group)
        .where((g) => g.isNotEmpty)
        .toSet()
        .toList();

    final result = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _WaypointDialog(initial: w, groups: existingGroups),
    );
    if (result == null) return;
    await _storage.upsert(result);
    await _load();
  }

  Future<void> _deleteWaypoint(Waypoint w) async {
    await _storage.delete(w.id);
    if (_activeId == w.id) _activeId = null;
    await _load();
  }

  Future<void> _exportPoints() async {
    if (_waypoints.isEmpty) {
      _snack('Нет точек для экспорта');
      return;
    }
    await showExportDialog(context, _waypoints);
  }

  Future<void> _importPoints() async {
    final changed = await showImportDialog(context, _storage);
    if (changed) {
      await _load();
    }
  }

  Future<void> _chooseSort() async {
    final chosen = await showSortMenu(context, _sortMode);
    if (chosen == null || chosen == _sortMode) return;
    setState(() => _sortMode = chosen);
  }

  Future<void> _showInfoDialog(String title, String text) async {
    await showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(text),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Понял'),
          ),
        ],
      ),
    );
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg)),
    );
  }

  List<Waypoint> _sortedFlat(List<Waypoint> src) {
    final list = [...src];
    switch (_sortMode) {
      case SortMode.byDate:
        list.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
        break;
      case SortMode.byName:
        list.sort((a, b) =>
            a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        break;
      case SortMode.byGroup:
        list.sort(
            (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        break;
    }
    return list;
  }

  List<MapEntry<String, List<Waypoint>>> _grouped() {
    final map = <String, List<Waypoint>>{};
    for (final w in _waypoints) {
      final g = w.group.isEmpty ? 'Без группы' : w.group;
      map.putIfAbsent(g, () => []).add(w);
    }
    for (final entry in map.entries) {
      entry