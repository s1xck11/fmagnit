import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/waypoint.dart';
import '../storage/waypoint_storage.dart';

/// Диалог экспорта: показывает JSON всех точек, можно скопировать.
Future<void> showExportDialog(
  BuildContext context,
  List<Waypoint> waypoints,
) async {
  final json = const JsonEncoder.withIndent('  ')
      .convert(waypoints.map((w) => w.toJson()).toList());

  await showDialog<void>(
    context: context,
    builder: (ctx) {
      final ctrl = TextEditingController(text: json);
      return AlertDialog(
        title: Text('Экспорт (${waypoints.length})'),
        content: SizedBox(
          width: double.maxFinite,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Скопируй текст и сохрани в заметках или отправь себе.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 8),
              Flexible(
                child: TextField(
                  controller: ctrl,
                  readOnly: true,
                  maxLines: null,
                  minLines: 6,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                  ),
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: json));
              if (ctx.mounted) {
                Navigator.of(ctx).pop();
                ScaffoldMessenger.of(ctx).showSnackBar(
                  const SnackBar(content: Text('Скопировано в буфер')),
                );
              }
            },
            child: const Text('Скопировать'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Закрыть'),
          ),
        ],
      );
    },
  );
}

/// Диалог импорта: вставляешь JSON, он добавляет новые точки.
/// Возвращает true, если что-то импортировано.
Future<bool> showImportDialog(
  BuildContext context,
  WaypointStorage storage,
) async {
  final ctrl = TextEditingController();
  bool busy = false;
  ImportResult? lastResult;

  final result = await showDialog<bool>(
    context: context,
    builder: (ctx) {
      return StatefulBuilder(
        builder: (ctx, setState) {
          Future<void> doImport() async {
            final text = ctrl.text.trim();
            if (text.isEmpty) return;
            setState(() {
              busy = true;
              lastResult = null;
            });
            final res = await storage.importFromJson(text);
            setState(() {
              busy = false;
              lastResult = res;
            });
          }

          return AlertDialog(
            title: const Text('Импорт точек'),
            content: SizedBox(
              width: double.maxFinite,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text(
                    'Вставь JSON из экспорта. Существующие точки '
                    'не удаляются. Дубликаты по координатам пропускаются.',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  Flexible(
                    child: TextField(
                      controller: ctrl,
                      maxLines: null,
                      minLines: 6,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 11,
                      ),
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        hintText: '[ { "name": "...", "latitude": ..., ... } ]',
                      ),
                    ),
                  ),
                  if (lastResult != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      lastResult!.error != null
                          ? lastResult!.error!
                          : 'Импортировано: ${lastResult!.added}, '
                              'пропущено: ${lastResult!.skipped}',
                      style: TextStyle(
                        fontSize: 12,
                        color: lastResult!.error != null
                            ? Colors.red
                            : Colors.green.shade800,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: busy ? null : () => Navigator.of(ctx).pop(false),
                child: const Text('Отмена'),
              ),
              FilledButton(
                onPressed: busy ? null : doImport,
                child: busy
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Импортировать'),
              ),
              if (lastResult != null && lastResult!.error == null)
                FilledButton.tonal(
                  onPressed: () => Navigator.of(ctx).pop(true),
                  child: const Text('Готово'),
                ),
            ],
          );
        },
      );
    },
  );

  return result == true;
}