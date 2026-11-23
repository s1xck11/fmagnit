import 'package:flutter/material.dart';

import '../models/waypoint.dart';
import '../services/nominatim_service.dart';
import '../services/overlay_service.dart';

/// Открывает диалог поиска адреса и возвращает выбранный Waypoint или null.
/// Источник: Яндекс (MapKit GEO), при недоступности — Nominatim (OSM).
Future<Waypoint?> showAddressSearchDialog(BuildContext context) async {
  return showDialog<Waypoint>(
    context: context,
    builder: (_) => const _AddressSearchDialog(),
  );
}

class _AddrResult {
  final String title;
  final String subtitle;
  final double latitude;
  final double longitude;

  _AddrResult({
    required this.title,
    required this.subtitle,
    required this.latitude,
    required this.longitude,
  });
}

class _AddressSearchDialog extends StatefulWidget {
  const _AddressSearchDialog();

  @override
  State<_AddressSearchDialog> createState() => _AddressSearchDialogState();
}

class _AddressSearchDialogState extends State<_AddressSearchDialog> {
  final _service = NominatimService();
  final _overlay = OverlayService();
  final _ctrl = TextEditingController();

  bool _loading = false;
  String? _error;
  String _source = '';
  List<_AddrResult> _results = [];

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final q = _ctrl.text.trim();
    if (q.isEmpty) return;

    setState(() {
      _loading = true;
      _error = null;
      _results = [];
      _source = '';
    });

    List<_AddrResult> out = [];
    String source = '';

    // 1) Яндекс
    try {
      final native = await _overlay.geocodeAddress(q);
      if (native != null && native.isNotEmpty) {
        out = native
            .map((m) => _AddrResult(
                  title: (m['name'] as String?) ?? '',
                  subtitle: (m['address'] as String?) ?? '',
                  latitude: (m['latitude'] as num).toDouble(),
                  longitude: (m['longitude'] as num).toDouble(),
                ))
            .toList();
        source = 'Яндекс';
      }
    } catch (_) {}

    // 2) Fallback: Nominatim
    if (out.isEmpty) {
      try {
        final res = await _service.search(q);
        out = res
            .map((r) => _AddrResult(
                  title: r.displayName,
                  subtitle: '',
                  latitude: r.latitude,
                  longitude: r.longitude,
                ))
            .toList();
        source = 'OSM';
      } catch (e) {
        if (!mounted) return;
        setState(() {
          _loading = false;
          _error = 'Ошибка: $e';
        });
        return;
      }
    }

    if (!mounted) return;
    setState(() {
      _loading = false;
      _results = out;
      _source = source;
      if (out.isEmpty) _error = 'Ничего не найдено';
    });
  }

  void _pick(_AddrResult r) {
    final base = r.title.isNotEmpty ? r.title : r.subtitle;
    final name = base.length > 60 ? '${base.substring(0, 60)}…' : base;
    Navigator.of(context).pop(
      Waypoint(
        name: name.isEmpty ? 'Точка' : name,
        latitude: r.latitude,
        longitude: r.longitude,
        accuracy: 5.0,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Поиск адреса'),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _ctrl,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: 'например, Ленина 5, Томск',
                suffixIcon: IconButton(
                  onPressed: _loading ? null : _search,
                  icon: const Icon(Icons.search),
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(16),
                child: CircularProgressIndicator(),
              )
            else if (_error != null)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Text(
                  _error!,
                  style: TextStyle(color: Colors.red.shade700),
                ),
              )
            else if (_results.isNotEmpty) ...[
              if (_source.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(
                    'Источник: $_source',
                    style: const TextStyle(
                      fontSize: 11,
                      color: Colors.grey,
                    ),
                  ),
                ),
              Flexible(
                child: ListView.separated(
                  shrinkWrap: true,
                  itemCount: _results.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) {
                    final r = _results[i];
                    return ListTile(
                      dense: true,
                      title: Text(
                        r.title.isNotEmpty ? r.title : r.subtitle,
                        style: const TextStyle(fontSize: 13),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        '${r.latitude.toStringAsFixed(5)}, '
                        '${r.longitude.toStringAsFixed(5)}'
                        '${r.subtitle.isNotEmpty && r.title.isNotEmpty ? '\n${r.subtitle}' : ''}',
                        style: const TextStyle(fontSize: 11),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      onTap: () => _pick(r),
                    );
                  },
                ),
              ),
            ],
          ],
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