import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../main.dart';
import '../services/mock_location_service.dart';
import '../services/overlay_service.dart';
import '../services/service_health.dart';

class HealthPage extends StatefulWidget {
  const HealthPage({super.key});

  @override
  State<HealthPage> createState() => _HealthPageState();
}

class _HealthPageState extends State<HealthPage> {
  final _overlay = OverlayService();
  final _mock = MockLocationService();

  ServiceHealth? _health;
  bool _loading = true;

  double _widthPct = 65;

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
    _loadWidth();
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

  Future<void> _loadWidth() async {
    final v = await _overlay.getHeaderWidthPct();
    if (!mounted) return;
    // clamp возвращает num — приводим к double явно
    final pct = v.toDouble().clamp(20.0, 100.0).toDouble();
    setState(() => _widthPct = pct);
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

  Widget _widthCard() {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: FMagnitTheme.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.tune, color: FMagnitTheme.accent, size: 22),
              SizedBox(width: 8),
              Text(
                'Оверлей',
                style: TextStyle(
                  color: FMagnitTheme.textPrimary,
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Ширина свёрнутой шапки',
                  style: TextStyle(
                    color: FMagnitTheme.textPrimary,
                    fontSize: 14,
                  ),
                ),
              ),
              Text(
                '${_widthPct.round()}%',
                style: const TextStyle(
                  color: FMagnitTheme.accent,
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                ),
              ),
            ],
          ),
          Semantics(
            label: 'Ширина свёрнутой шапки оверлея в процентах',
            slider: true,
            child: Slider(
              value: _widthPct,
              min: 20,
              max: 100,
              divisions: 16,
              label: '${_widthPct.round()}%',
              activeColor: FMagnitTheme.accent,
              inactiveColor: FMagnitTheme.inactive,
              onChanged: (v) => setState(() => _widthPct = v),
              onChangeEnd: (v) => _overlay.setHeaderWidthPct(v.round()),
            ),
          ),
          const Text(
            '20–100%, шаг 5%. Применяется к шапке сразу, даже при запущенном оверлее.',
            style: TextStyle(
              color: FMagnitTheme.textMuted,
              fontSize: 12,
            ),
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
        title: const Text('Настройки и здоровье'),
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
                _widthCard(),
                const SizedBox(height: 8),
                _item(
                  'Разрешение Mock Location',
                  'Выбор приложения для подмены в настройках разработчика',
                  h.mockPermission,
                  onOpen: () => _mock.openDeveloperSettings(),
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