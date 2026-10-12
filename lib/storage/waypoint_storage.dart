import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/waypoint.dart';

class WaypointStorage {
  static const String _fileName = 'waypoints.json';

  Future<File> _getFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/$_fileName');
  }

  Future<List<Waypoint>> loadAll() async {
    try {
      final file = await _getFile();
      if (!await file.exists()) return [];
      final raw = await file.readAsString();
      if (raw.trim().isEmpty) return [];
      final List<dynamic> data = jsonDecode(raw) as List<dynamic>;
      return data
          .map((e) => Waypoint.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveAll(List<Waypoint> waypoints) async {
    final file = await _getFile();
    final data = waypoints.map((w) => w.toJson()).toList();
    await file.writeAsString(jsonEncode(data));
  }

  Future<void> upsert(Waypoint waypoint) async {
    final list = await loadAll();
    final index = list.indexWhere((w) => w.id == waypoint.id);
    if (index >= 0) {
      list[index] = waypoint;
    } else {
      list.add(waypoint);
    }
    await saveAll(list);
  }

  Future<void> delete(String id) async {
    final list = await loadAll();
    list.removeWhere((w) => w.id == id);
    await saveAll(list);
  }
}