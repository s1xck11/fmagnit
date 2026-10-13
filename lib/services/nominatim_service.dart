import 'dart:convert';

import 'package:http/http.dart' as http;

class NominatimResult {
  final String displayName;
  final double latitude;
  final double longitude;

  NominatimResult({
    required this.displayName,
    required this.latitude,
    required this.longitude,
  });

  factory NominatimResult.fromJson(Map<String, dynamic> m) {
    return NominatimResult(
      displayName: (m['display_name'] as String?) ?? '',
      latitude: double.parse(m['lat'] as String),
      longitude: double.parse(m['lon'] as String),
    );
  }
}

class NominatimService {
  static const String _baseUrl =
      'https://nominatim.openstreetmap.org/search';

  // Nominatim требует уникальный User-Agent. Меняй email на свой,
  // если хочешь соблюсти их ToS полностью.
  static const String _userAgent =
      'fmagnit/0.1 (personal courier helper)';

  DateTime _lastRequestAt = DateTime.fromMillisecondsSinceEpoch(0);
  static const Duration _minInterval = Duration(milliseconds: 1100);

  Future<List<NominatimResult>> search(String query) async {
    final q = query.trim();
    if (q.isEmpty) return [];

    // Соблюдаем rate-limit: не чаще 1 запроса в ~1.1 сек
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