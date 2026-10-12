import 'package:flutter/material.dart';

import '../models/waypoint.dart';
import '../storage/waypoint_storage.dart';
import 'mock_test_panel.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _storage = WaypointStorage();
  List<Waypoint> _waypoints = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final list = await _storage.loadAll();
    setState(() {
      _waypoints = list;
      _loading = false;
    });
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
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('fmagnit — точки'),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addWaypoint,
        child: const Icon(Icons.add),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _waypoints.isEmpty
              ? const Center(
                  child: Text('Пока нет точек. Добавь первую!'),
                )
              : ListView.separated(
                  itemCount: _waypoints.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
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
    _latCtrl = TextEditingController(
      text: w?.latitude.toString() ?? '',
    );
    _lonCtrl = TextEditingController(
      text: w?.longitude.toString() ?? '',
    );
    _accCtrl = TextEditingController(
      text: (w?.accuracy ?? 3.0).toString(),
    );
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
            TextField(
              controller: _nameCtrl,
              decoration: const InputDecoration(labelText: 'Название'),
            ),
            TextField(
              controller: _latCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Latitude'),
            ),
            TextField(
              controller: _lonCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Longitude'),
            ),
            TextField(
              controller: _accCtrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
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