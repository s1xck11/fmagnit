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

  /// Импорт точек из JSON-строки. Возвращает (добавлено, пропущено).
  /// Дубликаты определяются по координатам, округлённым до 6 знаков.
  Future<ImportResult> importFromJson(String jsonString) async {
    final existing = await loadAll();
    final existingKeys = existing.map(_dupKey).toSet();

    final List<dynamic> data;
    try {
      final decoded = jsonDecode(jsonString);
      if (decoded is List) {
        data = decoded;
      } else if (decoded is Map<String, dynamic> && decoded['waypoints'] is List) {
        // Допускаем обёртку { "waypoints": [...] }
        data = decoded['waypoints'] as List;
      } else {
        throw const FormatException('Ожидался массив точек');
      }
    } catch (e) {
      return ImportResult(added: 0, skipped: 0, error: 'Некорректный JSON: $e');
    }

    int added = 0;
    int skipped = 0;

    for (final raw in data) {
      try {
        if (raw is! Map<String, dynamic>) {
          skipped++;
          continue;
        }
        final w = Waypoint.fromJson(raw);
        final key = _dupKey(w);
        if (existingKeys.contains(key)) {
          skipped++;
          continue;
        }
        existing.add(w);
        existingKeys.add(key);
        added++;
      } catch (_) {
        skipped++;
      }
    }

    await saveAll(existing);
    return ImportResult(added: added, skipped: skipped);
  }

  String _dupKey(Waypoint w) {
    final lat = w.latitude.toStringAsFixed(6);
    final lon = w.longitude.toStringAsFixed(6);
    return '$lat|$lon';
  }
}

class ImportResult {
  final int added;
  final int skipped;
  final String? error;

  ImportResult({required this.added, required this.skipped, this.error});
}