import 'dart:async';

import 'package:flutter/material.dart';

import '../services/mock_location_service.dart';

class MockTestPanel extends StatefulWidget {
  final double latitude;
  final double longitude;
  final String label;

  const MockTestPanel({
    super.key,
    required this.latitude,
    required this.longitude,
    required this.label,
  });

  @override
  State<MockTestPanel> createState() => _MockTestPanelState();
}

class _MockTestPanelState extends State<MockTestPanel> {
  final _service = MockLocationService();

  bool _mockEnabled = false;
  bool _running = false;
  String _status = 'Проверяю разрешение...';
  Timer? _autoStopTimer;

  @override
  void initState() {
    super.initState();
    _refreshPermission();
  }

  @override
  void dispose() {
    _autoStopTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshPermission() async {
    final enabled = await _service.isMockLocationEnabled();
    if (!mounted) return;
    setState(() {
      _mockEnabled = enabled;
      _status = enabled
          ? 'Разрешение на mock выдано'
          : 'Нет разрешения на mock. Открой настройки разработчика.';
    });
  }

  Future<void> _openSettings() async {
    await _service.openDeveloperSettings();
  }

  Future<void> _startTest() async {
    if (!_mockEnabled) {
      setState(() => _status = 'Сначала выдай разрешение на mock');
      return;
    }
    final ok = await _service.startMock(
      latitude: widget.latitude,
      longitude: widget.longitude,
      accuracy: 3.0,
      altitude: 0.0,
      intervalMs: 1000,
    );
    if (!mounted) return;
    setState(() {
      _running = ok;
      _status = ok
          ? 'Mock запущен на 10 сек: ${widget.label}'
          : 'Не удалось запустить mock';
    });

    _autoStopTimer?.cancel();
    if (ok) {
      _autoStopTimer = Timer(const Duration(seconds: 10), () {
        _stopTest();
      });
    }
  }

  Future<void> _stopTest() async {
    _autoStopTimer?.cancel();
    final ok = await _service.stopMock();
    if (!mounted) return;
    setState(() {
      _running = false;
      _status = ok ? 'Mock остановлен' : 'Не удалось остановить mock';
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      margin: const EdgeInsets.all(12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _mockEnabled ? Icons.check_circle : Icons.error_outline,
                  color: _mockEnabled ? Colors.green : Colors.orange,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Тест mock location',
                    style: theme.textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Точка: ${widget.label}\n'
              '${widget.latitude.toStringAsFixed(6)}, '
              '${widget.longitude.toStringAsFixed(6)}',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            Text(_status),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _refreshPermission,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Проверить разрешение'),
                ),
                OutlinedButton.icon(
                  onPressed: _openSettings,
                  icon: const Icon(Icons.settings),
                  label: const Text('Настройки разработчика'),
                ),
                FilledButton.icon(
                  onPressed: _running ? null : _startTest,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Запустить на 10 сек'),
                ),
                FilledButton.tonalIcon(
                  onPressed: _running ? _stopTest : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('Стоп'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}