import 'package:flutter/material.dart';

import '../main.dart';
import '../models/door_code.dart';
import '../storage/door_code_storage.dart';

class DoorCodesPage extends StatefulWidget {
  const DoorCodesPage({super.key});

  @override
  State<DoorCodesPage> createState() => _DoorCodesPageState();
}

class _DoorCodesPageState extends State<DoorCodesPage> {
  final _storage = DoorCodeStorage();
  List<DoorCode> _codes = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final list = await _storage.loadAll();
    if (!mounted) return;
    setState(() {
      _codes = list
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
      _loading = false;
    });
  }

  Future<void> _addOrEdit([DoorCode? initial]) async {
    final result = await showDialog<DoorCode>(
      context: context,
      builder: (_) => _DoorCodeDialog(initial: initial),
    );
    if (result == null) return;
    await _storage.upsert(result);
    await _load();
  }

  Future<void> _delete(DoorCode c) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Удалить код?'),
        content: Text('${c.code}${c.address.isNotEmpty ? '\n${c.address}' : ''}'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _storage.delete(c.id);
    await _load();
  }

  Future<void> _importFromJson() async {
    final controller = TextEditingController();
    final json = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Импорт кодов'),
        content: SizedBox(
          width: double.maxFinite,
          child: TextField(
            controller: controller,
            maxLines: 10,
            decoration: const InputDecoration(
              hintText: 'Вставь JSON сюда',
              border: OutlineInputBorder(),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text('Импорт'),
          ),
        ],
      ),
    );
    if (json == null || json.trim().isEmpty) return;

    final result = await _storage.importFromJson(json);
    await _load();
    if (!mounted) return;

    if (result.error != null) {
      _snack('Ошибка: ${result.error}');
    } else {
      _snack('Добавлено: ${result.added}, пропущено: ${result.skipped}');
    }
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg)),
    );
  }

  Widget _buildTile(DoorCode c) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
      child: Container(
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(16),
        ),
        child: ListTile(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          leading: const Icon(Icons.vpn_key, color: FMagnitTheme.accent),
          title: Text(
            c.code,
            style: const TextStyle(
              color: FMagnitTheme.textPrimary,
              fontWeight: FontWeight.bold,
              fontSize: 17,
            ),
          ),
          subtitle: c.address.isEmpty
              ? null
              : Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    c.address,
                    style: const TextStyle(
                      color: FMagnitTheme.textMuted,
                      fontSize: 12,
                    ),
                  ),
                ),
          onTap: () => _addOrEdit(c),
          trailing: IconButton(
            icon: const Icon(Icons.delete_outline,
                color: FMagnitTheme.textMuted),
            onPressed: () => _delete(c),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: FMagnitTheme.bg,
      appBar: AppBar(
        title: const Text('Коды домофонов'),
        actions: [
          IconButton(
            onPressed: _importFromJson,
            icon: const Icon(Icons.file_download_outlined),
            tooltip: 'Импорт',
          ),
        ],
      ),
      body: _loading
          ? const Center(
              child: CircularProgressIndicator(color: FMagnitTheme.accent),
            )
          : _codes.isEmpty
              ? const Center(
                  child: Text(
                    'Пока нет кодов.\nНажми + чтобы добавить.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: FMagnitTheme.textMuted,
                      fontSize: 15,
                    ),
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.only(bottom: 100, top: 6),
                  itemCount: _codes.length,
                  itemBuilder: (_, i) => _buildTile(_codes[i]),
                ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _addOrEdit(),
        tooltip: 'Добавить код',
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _DoorCodeDialog extends StatefulWidget {
  final DoorCode? initial;

  const _DoorCodeDialog({this.initial});

  @override
  State<_DoorCodeDialog> createState() => _DoorCodeDialogState();
}

class _DoorCodeDialogState extends State<_DoorCodeDialog> {
  late final TextEditingController _codeCtrl;
  late final TextEditingController _addrCtrl;
  late final TextEditingController _latCtrl;
  late final TextEditingController _lonCtrl;

  @override
  void initState() {
    super.initState();
    final c = widget.initial;
    _codeCtrl = TextEditingController(text: c?.code ?? '');
    _addrCtrl = TextEditingController(text: c?.address ?? '');
    _latCtrl = TextEditingController(
      text: c?.latitude.toString() ?? '',
    );
    _lonCtrl = TextEditingController(
      text: c?.longitude.toString() ?? '',
    );
  }

  @override
  void dispose() {
    _codeCtrl.dispose();
    _addrCtrl.dispose();
    _latCtrl.dispose();
    _lonCtrl.dispose();
    super.dispose();
  }

  void _save() {
    final code = _codeCtrl.text.trim();
    final address = _addrCtrl.text.trim();
    final lat = double.tryParse(_latCtrl.text.trim());
    final lon = double.tryParse(_lonCtrl.text.trim());

    if (code.isEmpty || lat == null || lon == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Проверь код и координаты')),
      );
      return;
    }

    final result = widget.initial != null
        ? widget.initial!.copyWith(
            code: code,
            address: address,
            latitude: lat,
            longitude: lon,
          )
        : DoorCode(
            code: code,
            address: address,
            latitude: lat,
            longitude: lon,
          );

    Navigator.of(context).pop(result);
  }

  @override
  Widget build(BuildContext context) {
    final title = widget.initial == null ? 'Новый код' : 'Редактировать код';
    return AlertDialog(
      title: Text(title),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _codeCtrl,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Код',
                hintText: 'например, 1234#',
                prefixIcon: Icon(Icons.vpn_key, size: 20),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _addrCtrl,
              decoration: const InputDecoration(
                labelText: 'Адрес (необязательно)',
                hintText: 'например, Карла Маркса 278',
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _latCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Latitude'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _lonCtrl,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(labelText: 'Longitude'),
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