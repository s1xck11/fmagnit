import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../models/waypoint.dart';
import '../services/mock_location_service.dart';
import '../services/overlay_service.dart';
import '../storage/waypoint_storage.dart';
import 'mock_test_panel.dart';

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

  Timer? _stateTimer;

  @override
  void initState() {
    super.initState();
    _load();
    _refreshPermissions();
    _overlay.setStartHandler(_onOverlayStartRequested);

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
    if (!mounted) return;
    if (running != _overlayRunning) {
      setState(() => _overlayRunning = running);
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

  Future<void> _addWaypoint() async {
    final result = await showDialog<Waypoint>(
      context: context,
      builder: (_) => const _WaypointDialog(),
    );
    if (result == null) return;
    await _storage.upsert(result);
    await _load();
  }

  Future<void> _editWaypoint(Waypoint w) async {
    final result = await showDialog<Waypoint>(
      context: context,
      builder: (_) => _WaypointDialog(initial: w),
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

  Widget _statusChip(String label, bool ok) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: ok ? Colors.green.shade800 : Colors.red.shade800,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: const TextStyle(color: Colors.white, fontSize: 12),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('fmagnit')),
      floatingActionButton: FloatingActionButton(
        onPressed: _addWaypoint,
        child: const Icon(Icons.add),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Оверлей поверх Магнит.Доставка',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Wrap(
                            spacing: 8,
                            runSpacing: 8,
                            children: [
                              _statusChip('Overlay', _overlayPerm),
                              _statusChip('Usage Access', _usagePerm),
                              _statusChip('Accessibility', _accessPerm),
                              _statusChip('Сервис', _overlayRunning),
                            ],
                          ),
                          const SizedBox(height: 12),
                          Wrap(
                            spacing: 8,
                            runSpacing: 8,
                            children: [
                              OutlinedButton.icon(
                                onPressed: () async {
                                  await _overlay.openUsageAccessSettings();
                                },
                                icon: const Icon(Icons.insights),
                                label: const Text('Usage Access'),
                              ),
                              OutlinedButton.icon(
                                onPressed: () async {
                                  await _overlay.openAccessibilitySettings();
                                },
                                icon: const Icon(Icons.accessibility),
                                label: const Text('Accessibility'),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: FilledButton.icon(
                                  onPressed: _toggleOverlay,
                                  icon: Icon(_overlayRunning
                                      ? Icons.stop
                                      : Icons.play_arrow),
                                  label: Text(
                                    _overlayRunning
                                        ? 'Остановить оверлей'
                                        : 'Запустить оверлей',
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              IconButton(
                                onPressed: _refreshPermissions,
                                icon: const Icon(Icons.refresh),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: _waypoints.isEmpty
                      ? const Center(
                          child: Text('Пока нет точек. Добавь первую!'),
                        )
                      : ListView.separated(
                          itemCount: _waypoints.length,
                          separatorBuilder: (_, __) =>
                              const Divider(height: 1),
                          itemBuilder: (context, i) {
                            final w = _waypoints[i];
                            return Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  title: Text(w.name),
                                  subtitle: Text(
                                    '${w.latitude.toStringAsFixed(6)}, '
                                    '${w.longitude.toStringAsFixed(6)}',
                                  ),
                                  onTap: () => _editWaypoint(w),
                                  trailing: IconButton(
                                    icon: const Icon(Icons.delete_outline),
                                    onPressed: () => _deleteWaypoint(w),
                                  ),
                                ),
                                MockTestPanel(
                                  label: w.name,
                                  latitude: w.latitude,
                                  longitude: w.longitude,
                                ),
                              ],
                            );
                          },
                        ),
                ),
              ],
            ),
    );
  }
}

class _WaypointDialog extends StatefulWidget {
  final Waypoint? initial;
  const _WaypointDialog({this.initial});

  @override
  State<_WaypointDialog> createState() => _WaypointDialogState();
}

class _WaypointDialogState extends State<_WaypointDialog> {
  late final TextEditingController _nameCtrl;
  late final TextEditingController _latCtrl;
  late final TextEditingController _lonCtrl;
  late final TextEditingController _accCtrl;

  @override
  void initState() {
    super.initState();
    final w = widget.initial;
    _nameCtrl = TextEditingController(text: w?.name ?? '');
    _latCtrl = TextEditingController(text: w?.latitude.toString() ?? '');
    _lonCtrl = TextEditingController(text: w?.longitude.toString() ?? '');
    _accCtrl = TextEditingController(text: (w?.accuracy ?? 3.0).toString());
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _latCtrl.dispose();
    _lonCtrl.dispose();
    _accCtrl.dispose();
    super.dispose();
  }

  void _save() {
    final name = _nameCtrl.text.trim();
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
            latitude: lat,
            longitude: lon,
            accuracy: acc,
          )
        : Waypoint(
            name: name,
            latitude: lat,
            longitude: lon,
            accuracy: acc,
          );

    Navigator.of(context).pop(w);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.initial == null ? 'Новая точка' : 'Редактировать'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField