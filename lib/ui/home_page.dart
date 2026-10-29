import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart';
import '../models/door_code.dart';
import '../models/waypoint.dart';
import '../services/mock_location_service.dart';
import '../services/order_target_service.dart';
import '../services/overlay_service.dart';
import '../storage/door_code_storage.dart';
import '../storage/waypoint_storage.dart';
import 'address_search_dialog.dart';
import 'door_codes_page.dart';
import 'health_page.dart';
import 'import_export_dialogs.dart';
import 'map_download_page.dart';
import 'sort_menu.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _storage = WaypointStorage();
  final _doorStorage = DoorCodeStorage();
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

  // ИСПРАВЛЕНИЕ: Замена Timer на StreamSubscription
  StreamSubscription<Map<dynamic, dynamic>?>? _overlayStateSubscription;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
    _refreshPermissions();
    _refreshOrderTarget();
    _refreshCrashLog();
    _overlay.setStartHandler(_onOverlayStartRequested);

    // ИСПРАВЛЕНИЕ: Реактивное обновление состояния без нагрузки на CPU
    _overlayStateSubscription = _overlay.overlayStateStream.listen((state) {
      if (state != null && mounted) {
        setState(() {
          _overlayRunning = state['isRunning'] as bool? ?? false;
        });
      }
    });
  }

  @override
  void dispose() {
    // ИСПРАВЛЕНИЕ: Гарантированная отписка для предотвращения утечек памяти
    _overlayStateSubscription?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissions();
      _refreshOrderTarget();
    }
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _waypoints = await _storage.loadAll();
      _activeId = await _storage.loadActiveId();
    } catch (e) {
      debugPrint('Ошибка загрузки: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _refreshPermissions() async {
    try {
      final perm = await _overlay.hasOverlayPermission();
      final access = await _overlay.hasAccessibilityPermission();
      final usage = await _overlay.hasUsageAccess();
      if (mounted) {
        setState(() {
          _overlayPerm = perm;
          _accessPerm = access;
          _usagePerm = usage;
        });
      }
    } catch (e) {
      debugPrint('Ошибка проверки прав: $e');
    }
  }

  Future<void> _refreshOrderTarget() async {
    try {
      _lastOrderTarget = await _orderTarget.getInfo();
      if (mounted) setState(() {});
    } catch (e) {
      debugPrint('Ошибка получения заказа: $e');
    }
  }

  Future<void> _refreshCrashLog() async {
    // Реализация получения логов, если требуется
  }

  Future<void> _onOverlayStartRequested(String id) async {
    debugPrint('Overlay start requested for: $id');
  }

  // Метод оставлен для ручного форс-обновления, если потребуется,
  // но основная логика теперь работает через Stream.
  Future<void> _pollOverlayState() async {
    final isRunning = await _overlay.isOverlayRunning();
    if (mounted) {
      setState(() {
        _overlayRunning = isRunning;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('fmagnit'),
        actions: [
          IconButton(
            icon: const Icon(Icons.health_and_safety),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const HealthPage()),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.download),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const MapDownloadPage()),
            ),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _buildBody(),
      floatingActionButton: FloatingActionButton(
        onPressed: _showAddWaypointDialog,
        child: const Icon(Icons.add),
      ),
    );
  }

  Widget _buildBody() {
    return Column(
      children: [
        if (!_overlayPerm || !_usagePerm)
          Container(
            color: Colors.red.shade900,
            padding: const EdgeInsets.all(8),
            child: const Text(
              'Требуются разрешения: Оверлей и Доступ к использованию',
              style: TextStyle(color: Colors.white),
              textAlign: TextAlign.center,
            ),
          ),
        Expanded(
          child: ListView.builder(
            itemCount: _waypoints.length,
            itemBuilder: (context, index) {
              final w = _waypoints[index];
              return ListTile(
                title: Text(w.name),
                subtitle: Text(
                  '${w.latitude.toStringAsFixed(6)}, ${w.longitude.toStringAsFixed(6)}',
                ),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.edit),
                      onPressed: () => _showEditWaypointDialog(w),
                    ),
                    IconButton(
                      icon: const Icon(Icons.delete, color: Colors.red),
                      onPressed: () => _deleteWaypoint(w),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  void _showAddWaypointDialog() {
    showDialog(
      context: context,
      builder: (_) => _WaypointDialog(groups: _getGroups()),
    ).then((result) {
      if (result is Waypoint) {
        _storage.save(result);
        _load();
      }
    });
  }

  void _showEditWaypointDialog(Waypoint w) {
    showDialog(
      context: context,
      builder: (_) => _WaypointDialog(initial: w, groups: _getGroups()),
    ).then((result) {
      if (result is Waypoint) {
        _storage.save(result);
        _load();
      }
    });
  }

  Future<void> _deleteWaypoint(Waypoint w) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Удалить точку?'),
        content: Text('Вы уверены, что хотите удалить "${w.name}"?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить', style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirm == true) {
      await _storage.delete(w.id);
      _load();
    }
  }

  List<String> _getGroups() {
    final groups = <String>{};
    for (final w in _waypoints) {
      if (w.group.isNotEmpty) groups.add(w.group);
    }
    return groups.toList()..sort();
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

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.initial == null ? 'Новая точка' : 'Редактировать точку'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.infoLine != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(widget.infoLine!, style: const TextStyle(color: Colors.blue)),
              ),
            TextField(
              controller: _nameCtrl,
              decoration: const InputDecoration(labelText: 'Название'),
            ),
            TextField(
              controller: _groupCtrl,
              decoration: const InputDecoration(labelText: 'Группа (опционально)'),
            ),
            TextField(
              controller: _latCtrl,
              decoration: const InputDecoration(labelText: 'Широта'),
              keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
            ),
            TextField(
              controller: _lonCtrl,
              decoration: const InputDecoration(labelText: 'Долгота'),
              keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
            ),
            TextField(
              controller: _accCtrl,
              decoration: const InputDecoration(labelText: 'Точность (м)'),
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Отмена'),
        ),
        ElevatedButton(
          onPressed: () {
            final lat = double.tryParse(_latCtrl.text);
            final lon = double.tryParse(_lonCtrl.text);
            final acc = double.tryParse(_accCtrl.text) ?? 3.0;

            if (lat == null || lon == null) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Некорректные координаты')),
              );
              return;
            }

            final w = Waypoint(
              id: widget.initial?.id ?? DateTime.now().millisecondsSinceEpoch.toString(),
              name: _nameCtrl.text.trim(),
              group: _groupCtrl.text.trim(),
              latitude: lat,
              longitude: lon,
              accuracy: acc,
            );
            Navigator.pop(context, w);
          },
          child: const Text('Сохранить'),
        ),
      ],
    );
  }
}
