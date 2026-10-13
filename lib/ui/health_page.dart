import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart';
import '../services/overlay_service.dart';
import '../services/service_health.dart';

class HealthPage extends StatefulWidget {
  const HealthPage({super.key});

  @override
  State<HealthPage> createState() => _HealthPageState();
}

class _HealthPageState extends State<HealthPage> {
  final _overlay = OverlayService();

  ServiceHealth? _health;
  bool _loading = true;

  static const String _emuiInstructions = '''
Как сделать, чтобы fmagnit не вырубался на Huawei (EMUI 12):

1. Настройки → Батарея → Запуск приложений
   → найди fmagnit
   → переключи на «Управлять вручную»
   → включи ВСЕ три тумблера:
     • Автозапуск
     • Косвенный запуск
     • Работа в фоне

2. Настройки → Батарея → Запуск приложений
   → тапни три точки справа сверху
   → «Отключить управление автоматически»
   (для всех или хотя бы для fmagnit)

3. Настройки → Батарея → Ещё настройки батареи
   → найди fmagnit
   → установи «Не оптимизировать» / «Разрешить»

4. Настройки → Приложения → fmagnit → Расход батареи
   → включи «Разрешить фоновую активность»

5. Настройки → Приложения → fmagnit → Расход данных
   → включи «Фоновые данные» и «Неограниченный расход данных»

6. Настройки → Специальные возможности → fmagnit
   → выключи и включи заново (если что-то отвалилось)

7. Не «очищай» fmagnit из недавних приложений — это убивает
   AccessibilityService до перезапуска вручную.

Если сделал всё — Accessibility продержится часами.
''';

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final h = await ServiceHealth.check();
    if (!mounted) return;
    setState(() {
      _health = h;
      _loading = false;
    });
  }

  Future<void> _copyInstructions() async {
    await Clipboard.setData(const ClipboardData(text: _emuiInstructions));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Инструкция скопирована')),
    );
  }

  Widget _item(
    String label,
    String desc,
    bool ok, {
    VoidCallback? onOpen,
  }) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: FMagnitTheme.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Icon(
            ok ? Icons.check_circle : Icons.error_outline,
            color: ok ? FMagnitTheme.success : FMagnitTheme.accent,
            size: 24,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    color: FMagnitTheme.textPrimary,
                    fontWeight: FontWeight.bold,
                    fontSize: 15,
                  ),
                ),
                if (desc.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      desc,
                      style: const TextStyle(
                        color: FMagnitTheme.textMuted,
                        fontSize: 12,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (!ok && onOpen != null)
            TextButton(
              onPressed: onOpen,
              child: const Text('Исправить'),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final h = _health;

    return Scaffold(
      backgroundColor: FMagnitTheme.bg,
      appBar: AppBar(
        title: const Text('Здоровье сервисов'),
        actions: [
          IconButton(
            onPressed: _refresh,
            icon: const Icon(Icons.refresh),
            tooltip: 'Обновить',
          ),
        ],
      ),
      body: _loading || h == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
              children: [
                _item(
                  'Разрешение Mock Location',
                  'Выбор приложения для подмены в настройках разработчика',
                  h.mockPermission,
                  onOpen: () => _overlay.openAccessibilitySettings(),
                ),
                _item(
                  'Разрешение «Поверх других окон»',
                  'Для оверлея',
                  h.overlayPermission,
                  onOpen: () => _overlay.requestOverlayPermission(),
                ),
                _item(
                  'Usage Access',
                  'Для определения активного приложения',
                  h.usageAccess,
                  onOpen: () => _overlay.openUsageAccessSettings(),
                ),
                _item(
                  'Accessibility Service',
                  'Вспомогательный сервис для фильтра оверлея. На EMUI часто '
                      'выключается системой — см. инструкцию ниже.',
                  h.accessibility,
                  onOpen: () => _overlay.openAccessibilitySettings(),
                ),
                _item(
                  'Overlay Service работает',
                  'Фоновый сервис оверлея',
                  h.overlayRunning,
                ),
                const SizedBox(height: 24),
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: FMagnitTheme.card,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(
                            Icons.battery_saver,
                            color: FMagnitTheme.accent,
                            size: 22,
                          ),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: Text(
                              'Настройки EMUI 12 (Huawei)',
                              style: TextStyle(
                                color: FMagnitTheme.textPrimary,
                                fontWeight: FontWeight.bold,
                                fontSize: 15,
                              ),
                            ),
                          ),
                          IconButton(
                            onPressed: _copyInstructions,
                            icon: const Icon(Icons.copy,
                                color: FMagnitTheme.textMuted),
                            tooltip: 'Скопировать инструкцию',
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        _emuiInstructions,
                        style: TextStyle(
                          color: FMagnitTheme.textPrimary,
                          fontSize: 13,
                          height: 1.5,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }
}