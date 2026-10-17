import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/door_code.dart';

class DoorCodeStorage {
  static const String _fileName = 'door_codes.json';

  Future<File> _getFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/$_fileName');
  }

  Future<List<DoorCode>> loadAll() async {
    try {
      final file = await _getFile();
      if (!await file.exists()) return [];
      final raw = await file.readAsString();
      if (raw.trim().isEmpty) return [];
      final List<dynamic> data = jsonDecode(raw) as List<dynamic>;
      return data
          .map((e) => DoorCode.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveAll(List<DoorCode> codes) async {
    final file = await _getFile();
    final data = codes.map((c) => c.toJson()).toList();
    await file.writeAsString(jsonEncode(data));
  }

  Future<void> upsert(DoorCode code) async {
    final list = await loadAll();
    final index = list.indexWhere((c) => c.id == code.id);
    if (index >= 0) {
      list[index] = code;
    } else {
      list.add(code);
    }
    await saveAll(list);
  }

  Future<void> delete(String id) async {
    final list = await loadAll();
    list.removeWhere((c) => c.id == id);
    await saveAll(list);
  }

  /// Импорт кодов из JSON-строки.
  ///
  /// Поддерживает два формата:
  /// 1) Родной: {"id": "...", "code": "...", "address": "...",
  ///             "latitude": 1.2, "longitude": 3.4}
  /// 2) Короткий (из Яндекса): {"name": "...", "doorCode": "...",
  ///             "lat": 1.2, "lon": 3.4, "address": "..."}
  ///
  /// Дубликаты — по паре (code, координаты до 6 знаков).
  Future<DoorImportResult> importFromJson(String jsonString) async {
    final existing = await loadAll();
    final existingKeys = existing.map(_dupKey).toSet();

    final List<dynamic> data;
    try {
      final decoded = jsonDecode(jsonString);
      if (decoded is List) {
        data = decoded;
      } else if (decoded is Map<String, dynamic> &&
          decoded['doorCodes'] is List) {
        data = decoded['doorCodes'] as List;
      } else {
        throw const FormatException('Ожидался массив кодов');
      }
    } catch (e) {
      return DoorImportResult(
          added: 0, skipped: 0, error: 'Некорректный JSON: $e');
    }

    int added = 0;
    int skipped = 0;

    for (final raw in data) {
      try {
        if (raw is! Map<String, dynamic>) {
          skipped++;
          continue;
        }
        final c = _doorCodeFromAny(raw);
        if (c == null) {
          skipped++;
          continue;
        }
        final key = _dupKey(c);
        if (existingKeys.contains(key)) {
          skipped++;
          continue;
        }
        existing.add(c);
        existingKeys.add(key);
        added++;
      } catch (_) {
        skipped++;
      }
    }

    await saveAll(existing);
    return DoorImportResult(added: added, skipped: skipped);
  }

  DoorCode? _doorCodeFromAny(Map<String, dynamic> raw) {
    // Родной формат
    if (raw['latitude'] != null &&
        raw['longitude'] != null &&
        raw['code'] != null) {
      return DoorCode.fromJson(raw);
    }

    // Короткий формат (Яндекс)
    final lat = raw['lat'];
    final lon = raw['lon'];
    if (lat is! num || lon is! num) return null;

    final code = (raw['doorCode'] as String?)?.trim() ?? '';
    if (code.isEmpty) return null;

    final name = (raw['name'] as String?)?.trim() ?? '';
    final address = (raw['address'] as String?)?.trim() ?? '';

    // Если name — это тоже код (из Яндекса name = doorCode), то в адрес
    // пихаем address. Если name отличается от code — склеиваем name + address.
    final addr = (name.isNotEmpty && name != code)
        ? (address.isEmpty ? name : '$name — $address')
        : address;

    return DoorCode(
      code: code,
      address: addr,
      latitude: lat.toDouble(),
      longitude: lon.toDouble(),
    );
  }

  String _dupKey(DoorCode c) {
    final lat = c.latitude.toStringAsFixed(6);
    final lon = c.longitude.toStringAsFixed(6);
    return '${c.code}|$lat|$lon';
  }
}

class DoorImportResult {
  final int added;
  final int skipped;
  final String? error;

  DoorImportResult({required this.added, required this.skipped, this.error});
}