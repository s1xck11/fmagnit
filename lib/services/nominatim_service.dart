import 'dart:convert';

import 'package:http/http.dart' as http;

class NominatimResult {
  final String displayName;
  final double latitude;
  final double longitude;
  /// Границы региона: [south, north, west, east] в градусах.
  /// Может быть пустым, если Nominatim не вернул boundingbox.
  final List<double> boundingBox;

  NominatimResult({
    required this.displayName,
    required this.latitude,
    required this.longitude,
    this.boundingBox = const [],
  });

  bool get hasBounds => boundingBox.length == 4;

  factory NominatimResult.fromJson(Map<String, dynamic> m) {
    List<double> bbox = const [];
    try {
      final raw = m['boundingbox'];
      if (raw is List && raw.length == 4) {
        bbox = raw.map((e) => double.parse(e.toString())).toList();
      }
    } catch (_) {}
    return NominatimResult(
      displayName: (m['display_name'] as String?) ?? '',
      latitude: double.parse(m['lat'] as String),
      longitude: double.parse(m['lon'] as String),
      boundingBox: bbox,
    );
  }
}

class NominatimService {
  static const String _baseUrl =
      'https://nominatim.openstreetmap.org/search';

  static const String _userAgent =
      'fmagnit/0.1 (personal courier helper)';

  DateTime _lastRequestAt = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _minInterval = Duration(milliseconds: 1100);

  Future<List<NominatimResult>> search(String query) async {
    final q = query.trim();
    if (q.isEmpty) return [];

    final now = DateTime.now();
    final since = now.difference(_lastRequestAt);
    if (since < _minInterval) {
      await Future.delayed(_minInterval - since);
    }
    _lastRequestAt = DateTime.now();

    final uri = Uri.parse(_baseUrl).replace(queryParameters: {
      'q': q,
      'format': 'json',
      'limit': '10',
      'accept-language': 'ru',
      'addressdetails': '0',
    });

    final resp = await http.get(
      uri,
      headers: {
        'User-Agent': _userAgent,
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 15));

    if (resp.statusCode != 200) {
      throw Exception('Nominatim вернул код ${resp.statusCode}');
    }

    final List<dynamic> data = jsonDecode(resp.body) as List<dynamic>;
    return data
        .map((e) => NominatimResult.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}