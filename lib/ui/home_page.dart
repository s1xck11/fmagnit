import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart';
import '../models/waypoint.dart';
import '../services/mock_location_service.dart';
import '../services/order_target_service.dart';
import '../services/overlay_service.dart';
import '../storage/waypoint_storage.dart';
import 'address_search_dialog.dart';
import 'health_page.dart';
import 'import_export_dialogs.dart';
import 'sort_menu.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _storage = WaypointStorage();
  final _overlay = OverlayService();
  final _mock = MockLocationService();
  final _orderTarget = OrderTargetService();

  List<Waypoint> _waypoints = [];
  bool _loading = true;

  bool _overlayRunning = false;
  bool _overlayPerm = false;
  bool _accessPerm = false;
  bool _usagePerm = false;
  String? _activeId;

  OrderTargetInfo? _lastOrderTarget;
  CrashInfo? _lastCrash;

  SortMode _sortMode = SortMode.byDate;
  final Set<String> _collapsedGroups = {};

  Timer? _stateTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
    _refreshPermissions();
    _refreshOrderTarget();
    _refreshCrashLog();
    _overlay.setStartHandler(_onOverlayStartRequested);

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _mock.forceResetMock();
    });

    _stateTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      _pollOverlayState();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stateTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissions();
      _refreshOrderTarget();
      _refreshCrashLog();
    }
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

  Future<void> _refreshOrderTarget() async {
    final last = await _orderTarget.getLast();
    if (!mounted) return;
    setState(() => _lastOrderTarget = last);
  }

  Future<void> _refreshCrashLog() async {
    final c = await _orderTarget.getLastCrash();
    if (!mounted) return;
    setState(() => _lastCrash = c);
  }

  Future<void> _onOverlayStartRequested(String id) async {
    if (id == '__order__') {
      final last = await _orderTarget.getLast();
      if (last != null && last.hasData) {
        await _mock.startMock(
          latitude: last.latitude!,
          longitude: last.longitude!,
          accuracy: 5.0,
        );
      }
      return;
    }

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
    if (!_accessPerm) {
      await _overlay.openAccessibilitySettings();
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

  Future<void> _testOrder() async {
    if (_waypoints.isEmpty) {
      _snack('Сначала добавь хотя бы одну точку');
      return;
    }

    final selected = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _PickWaypointDialog(waypoints: _waypoints),
    );
    if (selected == null) return;

    await _overlay.setManualOrderTarget(
      latitude: selected.latitude,
      longitude: selected.longitude,
    );

    await Future.delayed(const Duration(milliseconds: 500));
    await _refreshOrderTarget();

    if (!mounted) return;
    _snack('Тестовый заказ: ${selected.name}');
  }

  Future<void> _showAddSheet() async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Text(
                'Добавить точку',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 16,
                  color: FMagnitTheme.textPrimary,
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.my_location,
                  color: FMagnitTheme.accent),
              title: const Text('Сохранить мою позицию'),
              onTap: () => Navigator.of(context).pop('current'),
            ),
            ListTile(
              leading: const Icon(Icons.edit_location_alt,
                  color: FMagnitTheme.accent),
              title: const Text('Ввести координаты вручную'),
              onTap: () => Navigator.of(context).pop('manual'),
            ),
            ListTile(
              leading: const Icon(Icons.search, color: FMagnitTheme.accent),
              title: const Text('Найти по адресу'),
              onTap: () => Navigator.of(context).pop('search'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );

    if (action == 'current') {
      await _addCurrentLocation();
    } else if (action == 'manual') {
      await _addWaypoint();
    } else if (action == 'search') {
      await _searchByAddress();
    }
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
      entry.value.sort(
        (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()),
      );
    }
    final keys = map.keys.toList()
      ..sort((a, b) {
        if (a == 'Без группы') return 1;
        if (b == 'Без группы') return -1;
        return a.toLowerCase().compareTo(b.toLowerCase());
      });
    return keys.map((k) => MapEntry(k, map[k]!)).toList();
  }

  Widget _buildWaypointTile(Waypoint w) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
      child: Container(
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(16),
        ),
        child: ListTile(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: Text(
            w.name,
            style: const TextStyle(
              color: FMagnitTheme.textPrimary,
              fontWeight: FontWeight.w600,
              fontSize: 15,
            ),
          ),
          subtitle: Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              '${w.latitude.toStringAsFixed(6)}, '
              '${w.longitude.toStringAsFixed(6)}'
              '${w.group.isNotEmpty ? '  •  ${w.group}' : ''}',
              style: const TextStyle(
                color: FMagnitTheme.textMuted,
                fontSize: 12,
              ),
            ),
          ),
          onTap: () => _editWaypoint(w),
          trailing: IconButton(
            icon: const Icon(Icons.delete_outline,
                color: FMagnitTheme.textMuted),
            onPressed: () => _deleteWaypoint(w),
          ),
        ),
      ),
    );
  }

  Widget _buildGroupedList() {
    final groups = _grouped();
    final children = <Widget>[];
    for (final entry in groups) {
      final name = entry.key;
      final items = entry.value;
      final collapsed = _collapsedGroups.contains(name);
      children.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () {
              setState(() {
                if (collapsed) {
                  _collapsedGroups.remove(name);
                } else {
                  _collapsedGroups.add(name);
                }
              });
            },
            child: Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
              child: Row(
                children: [
                  Icon(
                    collapsed ? Icons.chevron_right : Icons.expand_more,
                    size: 20,
                    color: FMagnitTheme.accent,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '$name (${items.length})',
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 15,
                        color: FMagnitTheme.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      if (!collapsed) {
        for (final w in items) {
          children.add(_buildWaypointTile(w));
        }
      }
    }
    return ListView(
      padding: const EdgeInsets.only(bottom: 100),
      children: children,
    );
  }

  Widget _buildFlatList() {
    final sorted = _sortedFlat(_waypoints);
    return ListView.builder(
      padding: const EdgeInsets.only(bottom: 100),
      itemCount: sorted.length,
      itemBuilder: (context, i) => _buildWaypointTile(sorted[i]),
    );
  }

  Widget _statusDot(bool ok) {
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(
        color: ok ? FMagnitTheme.success : FMagnitTheme.accent,
        shape: BoxShape.circle,
      ),
    );
  }

  Widget _statusRow() {
    final ok = _overlayPerm && _usagePerm && _accessPerm;
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: _openHealth,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          children: [
            _statusDot(_overlayPerm),
            const SizedBox(width: 6),
            const Text('Overlay',
                style: TextStyle(
                    color: FMagnitTheme.textMuted, fontSize: 11)),
            const SizedBox(width: 12),
            _statusDot(_usagePerm),
            const SizedBox(width: 6),
            const Text('Usage',
                style: TextStyle(
                    color: FMagnitTheme.textMuted, fontSize: 11)),
            const SizedBox(width: 12),
            _statusDot(_accessPerm),
            const SizedBox(width: 6),
            const Text('Accessibility',
                style: TextStyle(
                    color: FMagnitTheme.textMuted, fontSize: 11)),
            const Spacer(),
            Text(
              ok ? 'OK' : 'Нажми сюда',
              style: TextStyle(
                color: ok
                    ? FMagnitTheme.success
                    : FMagnitTheme.accent,
                fontSize: 11,
                fontWeight: FontWeight.bold,
              ),
            ),
            const Icon(Icons.chevron_right,
                color: FMagnitTheme.textMuted, size: 16),
          ],
        ),
      ),
    );
  }

  Widget _orderTargetBanner() {
    final t = _lastOrderTarget;
    if (t == null || !t.hasData) return const SizedBox.shrink();

    final lat = t.latitude!.toStringAsFixed(6);
    final lon = t.longitude!.toStringAsFixed(6);
    final dt = t.receivedAt;
    final time =
        '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}:${dt.second.toString().padLeft(2, '0')}';

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: FMagnitTheme.accent),
        ),
        child: Row(
          children: [
            const Icon(Icons.check_circle, color: FMagnitTheme.accent),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Последний перехват',
                    style: TextStyle(
                      color: FMagnitTheme.textPrimary,
                      fontWeight: FontWeight.bold,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '$lat, $lon   •   $time',
                    style: const TextStyle(
                      color: FMagnitTheme.textMuted,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Очистить',
              icon: const Icon(Icons.close, color: FMagnitTheme.textMuted),
              onPressed: () async {
                await _overlay.clearOrderTarget();
                await _refreshOrderTarget();
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _crashBanner() {
    final c = _lastCrash;
    if (c == null || c.log.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.red.shade900.withOpacity(0.4),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: FMagnitTheme.accent),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.bug_report, color: FMagnitTheme.accent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Краш [${c.tag ?? "?"}]',
                    style: const TextStyle(
                      color: FMagnitTheme.textPrimary,
                      fontWeight: FontWeight.bold,
                      fontSize: 13,
                    ),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, color: FMagnitTheme.textMuted),
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: c.log));
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Лог скопирован')),
                      );
                    }
                  },
                ),
                IconButton(
                  icon: const Icon(Icons.close, color: FMagnitTheme.textMuted),
                  onPressed: () async {
                    await _orderTarget.clearCrash();
                    await _refreshCrashLog();
                  },
                ),
              ],
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 120,
              child: SingleChildScrollView(
                child: Text(
                  c.log,
                  style: const TextStyle(
                    color: FMagnitTheme.textPrimary,
                    fontSize: 10,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: FMagnitTheme.bg,
      appBar: AppBar(
        title: const Text('fmagnit'),
        actions: [
          IconButton(
            onPressed: _chooseSort,
            icon: Icon(_sortMode.icon),
            tooltip: 'Сортировка: ${_sortMode.label}',
          ),
          IconButton(
            onPressed: _searchByAddress,
            icon: const Icon(Icons.search),
            tooltip: 'Поиск адреса',
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            tooltip: 'Ещё',
            onSelected: (v) {
              switch (v) {
                case 'test_order':
                  _testOrder();
                  break;
                case 'restore_gps':
                  _restoreGps();
                  break;
                case 'refresh_target':
                  _refreshOrderTarget();
                  break;
                case 'refresh_crash':
                  _refreshCrashLog();
                  break;
                case 'settings':
                  _openHealth();
                  break;
                case 'import':
                  _importPoints();
                  break;
                case 'export':
                  _exportPoints();
                  break;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'test_order',
                child: Row(
                  children: [
                    Icon(Icons.assignment_turned_in_outlined, size: 20),
                    SizedBox(width: 12),
                    Text('Тестовый заказ'),
                  ],
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                value: 'restore_gps',
                child: Row(
                  children: [
                    Icon(Icons.gps_fixed, size: 20),
                    SizedBox(width: 12),
                    Text('Восстановить GPS'),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'refresh_target',
                child: Row(
                  children: [
                    Icon(Icons.refresh, size: 20),
                    SizedBox(width: 12),
                    Text('Обновить перехват'),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'refresh_crash',
                child: Row(
                  children: [
                    Icon(Icons.bug_report, size: 20),
                    SizedBox(width: 12),
                    Text('Обновить лог краша'),
                  ],
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                value: 'settings',
                child: Row(
                  children: [
                    Icon(Icons.health_and_safety, size: 20),
                    SizedBox(width: 12),
                    Text('Настройки сервисов'),
                  ],
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                value: 'import',
                child: Row(
                  children: [
                    Icon(Icons.file_download_outlined, size: 20),
                    SizedBox(width: 12),
                    Text('Импорт'),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'export',
                child: Row(
                  children: [
                    Icon(Icons.file_upload_outlined, size: 20),
                    SizedBox(width: 12),
                    Text('Экспорт'),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
      body: _loading
          ? const Center(
              child: CircularProgressIndicator(color: FMagnitTheme.accent),
            )
          : Column(
              children: [
                // --- Секция Управление ---
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
                  child: Container(
                    decoration: BoxDecoration(
                      color: FMagnitTheme.card,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        FilledButton.icon(
                          onPressed: _toggleOverlay,
                          style: FilledButton.styleFrom(
                            backgroundColor: _overlayRunning
                                ? FMagnitTheme.accent
                                : FMagnitTheme.inactive,
                            foregroundColor: _overlayRunning
                                ? Colors.white
                                : FMagnitTheme.textMuted,
                            minimumSize: const Size.fromHeight(52),
                          ),
                          icon: Icon(
                            _overlayRunning ? Icons.stop : Icons.play_arrow,
                            size: 22,
                          ),
                          label: Text(
                            _overlayRunning
                                ? 'Остановить оверлей'
                                : 'Запустить оверлей',
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                        const SizedBox(height: 6),
                        _statusRow(),
                      ],
                    ),
                  ),
                ),
                _orderTargetBanner(),
                _crashBanner(),
                // --- Секция Точки ---
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
                  child: Row(
                    children: [
                      const Text(
                        'Точки',
                        style: TextStyle(
                          color: FMagnitTheme.textPrimary,
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      const Spacer(),
                      Text(
                        '${_waypoints.length}',
                        style: const TextStyle(
                          color: FMagnitTheme.textMuted,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: _waypoints.isEmpty
                      ? const Center(
                          child: Text(
                            'Пока нет точек.\nНажми + чтобы добавить.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              color: FMagnitTheme.textMuted,
                              fontSize: 15,
                            ),
                          ),
                        )
                      : (_sortMode == SortMode.byGroup
                          ? _buildGroupedList()
                          : _buildFlatList()),
                ),
              ],
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: _showAddSheet,
        tooltip: 'Добавить точку',
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _PickWaypointDialog extends StatelessWidget {
  final List<Waypoint> waypoints;

  const _PickWaypointDialog({required this.waypoints});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Тестовый заказ'),
      content: SizedBox(
        width: double.maxFinite,
        child: ListView.builder(
          shrinkWrap: true,
          itemCount: waypoints.length,
          itemBuilder: (_, i) {
            final w = waypoints[i];
            return ListTile(
              dense: true,
              title: Text(w.name),
              subtitle: Text(
                '${w.latitude.toStringAsFixed(6)}, '
                '${w.longitude.toStringAsFixed(6)}'
                '${w.group.isNotEmpty ? '  •  ${w.group}' : ''}',
                style: const TextStyle(fontSize: 11),
              ),
              onTap: () => Navigator.of(context).pop(w),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Отмена'),
        ),
      ],
    );
  }
}

class _WaypointDialog extends StatefulWidget {
  final Waypoint? initial;
  final double? presetLat;
  final double? presetLon;
  final double? presetAcc;
  final String? infoLine;
  final List<String> groups;

  const _WaypointDialog({
    this.initial,
    this.presetLat,
    this.presetLon,
    this.presetAcc,
    this.infoLine,
    this.groups = const [],
  });

  @override
  State<_WaypointDialog> createState() => _WaypointDialogState();
}

class _WaypointDialogState extends State<_WaypointDialog> {
  late final TextEditingController _nameCtrl;
  late final TextEditingController _groupCtrl;
  late final TextEditingController _latCtrl;
  late final TextEditingController _lonCtrl;
  late final TextEditingController _accCtrl;

  @override
  void initState() {
    super.initState();
    final w = widget.initial;
    _nameCtrl = TextEditingController(text: w?.name ?? '');
    _groupCtrl = TextEditingController(text: w?.group ?? '');
    _latCtrl = TextEditingController(
      text: (w?.latitude ?? widget.presetLat)?.toString() ?? '',
    );
    _lonCtrl = TextEditingController(
      text: (w?.longitude ?? widget.presetLon)?.toString() ?? '',
    );
    _accCtrl = TextEditingController(
      text: (w?.accuracy ?? widget.presetAcc ?? 3.0).toString(),
    );
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _groupCtrl.dispose();
    _latCtrl.dispose();
    _lonCtrl.dispose();
    _accCtrl.dispose();
    super.dispose();
  }

  void _save() {
    final name = _nameCtrl.text.trim();
    final group = _groupCtrl.text.trim();
    final lat = double.tryParse(_latCtrl.text.trim());
    final lon = double.tryParse(_lonCtrl.text.trim());
    final acc = double.tryParse(_accCtrl.text.trim()) ?? 3.0;

    if (name.isEmpty || lat == null || lon == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Проверь название и координаты')),
      );
      return;
    }

    final w = widget.initial != null
        ? widget.initial!.copyWith(
            name: name,
            group: group,
            latitude: lat,
            longitude: lon,
            accuracy: acc,
          )
        : Waypoint(
            name: name,
            group: group,
            latitude: lat,
            longitude: lon,
            accuracy: acc,
          );

    Navigator.of(context).pop(w);
  }

  @override
  Widget build(BuildContext context) {
    final title = widget.initial == null ? 'Новая точка' : 'Редактировать';
    return AlertDialog(
      title: Text(title),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.infoLine != null) ...[
              Text(
                widget.infoLine!,
                style: const TextStyle(
                  fontSize: 12,
                  color: FMagnitTheme.textMuted,
                ),
              ),
              const SizedBox(height: 8),
            ],
            TextField(
              controller: _nameCtrl,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Название',
                hintText: 'например, Магнит на Ленина 5',
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _groupCtrl,
              decoration: InputDecoration(
                labelText: 'Группа (необязательно)',
                hintText: 'например, Смена 13.10',
                suffixIcon: widget.groups.isEmpty
                    ? null
                    : PopupMenuButton<String>(
                        icon: const Icon(Icons.arrow_drop_down),
                        tooltip: 'Выбрать существующую',
                        onSelected: (v) {
                          _groupCtrl.text = v;
                        },
                        itemBuilder: (_) => widget.groups
                            .map((g) => PopupMenuItem(
                                  value: g,
                                  child: Text(g),
                                ))
                            .toList(),
                      ),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _latCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Latitude'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _lonCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Longitude'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _accCtrl,
              keyboardType:
                  const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Accuracy (m)'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Отмена'),
        ),
        FilledButton(
          onPressed: _save,
          child: const Text('Сохранить'),
        ),
      ],
    );
  }
}