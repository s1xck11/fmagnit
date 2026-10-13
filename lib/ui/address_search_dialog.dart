import 'package:flutter/material.dart';

import '../models/waypoint.dart';
import '../services/nominatim_service.dart';

/// Открывает диалог поиска адреса и возвращает выбранный Waypoint или null.
Future<Waypoint?> showAddressSearchDialog(BuildContext context) async {
  return showDialog<Waypoint>(
    context: context,
    builder: (_) => const _AddressSearchDialog(),
  );
}

class _AddressSearchDialog extends StatefulWidget {
  const _AddressSearchDialog();

  @override
  State<_AddressSearchDialog> createState() => _AddressSearchDialogState();
}

class _AddressSearchDialogState extends State<_AddressSearchDialog> {
  final _service = NominatimService();
  final _ctrl = TextEditingController();

  bool _loading = false;
  String? _error;
  List<NominatimResult> _results = [];

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
    });

    try {
      final res = await _service.search(q);
      if (!mounted) return;
      setState(() {
        _loading = false;
        _results = res;
        if (res.isEmpty) _error = 'Ничего не найдено';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Ошибка: $e';
      });
    }
  }

  void _pick(NominatimResult r) {
    // Имя по умолчанию — первые 60 символов display_name
    final name = r.displayName.length > 60
        ? '${r.displayName.substring(0, 60)}…'
        : r.displayName;
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
            else if (_results.isNotEmpty)
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
                        r.displayName,
                        style: const TextStyle(fontSize: 13),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        '${r.latitude.toStringAsFixed(5)}, '
                        '${r.longitude.toStringAsFixed(5)}',
                        style: const TextStyle(fontSize: 11),
                      ),
                      onTap: () => _pick(r),
                    );
                  },
                ),
              ),
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