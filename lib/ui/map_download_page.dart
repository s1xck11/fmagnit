import 'dart:async';

import 'package:flutter/material.dart';

import '../main.dart';
import '../services/map_download_service.dart';
import '../services/nominatim_service.dart';

class MapDownloadPage extends StatefulWidget {
  const MapDownloadPage({super.key});

  @override
  State<MapDownloadPage> createState() => _MapDownloadPageState();
}

class _MapDownloadPageState extends State<MapDownloadPage> {
  final _service = MapDownloadService();
  final _nominatim = NominatimService();
  final _searchCtrl = TextEditingController();

  bool _searching = false;
  String? _searchError;
  List<NominatimResult> _results = [];

  MapDownloadProgress _progress = MapDownloadProgress.idle();
  Timer? _progressTimer;

  List<DownloadedRegion> _regions = [];

  int _minZoom = 12;
  int _maxZoom = 17;

  @override
  void initState() {
    super.initState();
    _loadRegions();
    _refreshProgress();
    _progressTimer = Timer.periodic(const Duration(milliseconds: 800), (_) {
      _refreshProgress();
    });
  }

  @override
  void dispose() {
    _progressTimer?.cancel();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _refreshProgress() async {
    final p = await _service.getProgress();
    if (!mounted) return;
    final wasFinished = _progress.finished;
    setState(() => _progress = p);
    if (p.finished && !wasFinished) {
      await _loadRegions();
    }
  }

  Future<void> _loadRegions() async {
    final list = await _service.getDownloadedRegions();
    if (!mounted) return;
    setState(() => _regions = list);
  }

  Future<void> _search() async {
    final q = _searchCtrl.text.trim();
    if (q.isEmpty) return;
    setState(() {
      _searching = true;
      _searchError = null;
      _results = [];
    });
    try {
      final res = await _nominatim.search(q);
      if (!mounted) return;
      setState(() {
        _searching = false;
        _results = res;
        if (res.isEmpty) _searchError = 'Ничего не найдено';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _searching = false;
        _searchError = 'Ошибка: $e';
      });
    }
  }

  Future<void> _confirmDownload(NominatimResult r) async {
    if (!r.hasBounds) {
      _snack('У этого места нет границ. Выбери другой результат.');
      return;
    }

    final south = r.boundingBox[0];
    final north = r.boundingBox[1];
    final west = r.boundingBox[2];
    final east = r.boundingBox[3];

    final tiles = MapDownloadService.estimateTiles(
      south: south,
      north: north,
      west: west,
      east: east,
      minZoom: _minZoom,
      maxZoom: _maxZoom,
    );

    // Примерно 30 КБ на тайл
    final mb = (tiles * 30 / 1024).toStringAsFixed(0);

    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Скачать карту?'),
        content: Text(
          'Регион: ${r.displayName}\n\n'
          'Зумы: $_minZoom – $_maxZoom\n'
          'Примерно тайлов: $tiles\n'
          'Примерный размер: ~$mb МБ\n\n'
          'Скачивание может занять 5–15 минут на Wi-Fi. '
          'Во время скачивания приложение должно оставаться в фоне. '
          'Не выключай экран.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Скачать'),
          ),
        ],
      ),
    );
    if (ok != true) return;

    await _service.startDownload(
      name: r.displayName,
      south: south,
      north: north,
      west: west,
      east: east,
      minZoom: _minZoom,
      maxZoom: _maxZoom,
    );
    await _refreshProgress();
  }

  Future<void> _cancel() async {
    await _service.cancelDownload();
    await _refreshProgress();
  }

  Future<void> _deleteRegion(DownloadedRegion r) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Удалить регион?'),
        content: Text(
          'Удалить скачанные тайлы для "${r.name}"?\n'
          'Если этот регион нужен снова — придётся скачать заново.',
        ),
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
    await _service.deleteRegion(r.id);
    await _loadRegions();
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Widget _zoomRow() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          const Text(
            'Детализация',
            style: TextStyle(
              color: FMagnitTheme.textPrimary,
              fontWeight: FontWeight.bold,
            ),
          ),
          const Spacer(),
          _zoomChip('$_minZoom', () {
            setState(() => _minZoom = _minZoom.clamp(8, _maxZoom));
          }, decrement: true),
          const SizedBox(width: 8),
          Text(
            '$_minZoom – $_maxZoom',
            style: const TextStyle(
              color: FMagnitTheme.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(width: 8),
          _zoomChip('$_maxZoom', () {
            setState(() => _maxZoom = _maxZoom.clamp(_minZoom, 19));
          }, decrement: false),
        ],
      ),
    );
  }

  Widget _zoomChip(String label, VoidCallback onTap, {required bool decrement}) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        width: 36,
        height: 36,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: FMagnitTheme.divider),
        ),
        child: Text(
          decrement ? '−' : '+',
          style: const TextStyle(
            color: FMagnitTheme.textPrimary,
            fontSize: 18,
            fontWeight: FontWeight.bold,
          ),
        ),
      ),
    );
  }

  Widget _progressCard() {
    final p = _progress;
    if (!p.running && !p.finished && p.error == null) {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: FMagnitTheme.accent),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.download, color: FMagnitTheme.accent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    p.error != null
                        ? 'Ошибка: ${p.error}'
                        : (p.finished
                            ? 'Скачивание завершено'
                            : 'Скачивание: ${p.regionName ?? "..."}'),
                    style: const TextStyle(
                      color: FMagnitTheme.textPrimary,
                      fontWeight: FontWeight.bold,
                      fontSize: 13,
                    ),
                  ),
                ),
                if (p.running)
                  IconButton(
                    icon: const Icon(Icons.close,
                        color: FMagnitTheme.textMuted),
                    onPressed: _cancel,
                    tooltip: 'Отменить',
                  ),
              ],
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: p.fraction,
              backgroundColor: FMagnitTheme.inactive,
              valueColor: const AlwaysStoppedAnimation(FMagnitTheme.accent),
            ),
            const SizedBox(height: 6),
            Text(
              '${p.downloaded} / ${p.total} тайлов',
              style: const TextStyle(
                color: FMagnitTheme.textMuted,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _regionTile(DownloadedRegion r) {
    final size = (r.tileCount * 30 / 1024).toStringAsFixed(0);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Container(
        decoration: BoxDecoration(
          color: FMagnitTheme.card,
          borderRadius: BorderRadius.circular(14),
        ),
        child: ListTile(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
          title: Text(
            r.name,
            style: const TextStyle(
              color: FMagnitTheme.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            'Зумы ${r.minZoom}–${r.maxZoom}  •  ${r.tileCount} тайлов (~$size МБ)',
            style: const TextStyle(
              color: FMagnitTheme.textMuted,
              fontSize: 11,
            ),
          ),
          trailing: IconButton(
            icon: const Icon(Icons.delete_outline,
                color: FMagnitTheme.textMuted),
            onPressed: () => _deleteRegion(r),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: FMagnitTheme.bg,
      appBar: AppBar(title: const Text('Загрузка карт')),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              'Скачай карту города заранее, чтобы она работала без интернета',
              style: TextStyle(
                color: FMagnitTheme.textMuted,
                fontSize: 13,
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextField(
              controller: _searchCtrl,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: 'например, Ижевск',
                suffixIcon: IconButton(
                  onPressed: _searching ? null : _search,
                  icon: const Icon(Icons.search),
                ),
              ),
            ),
          ),
          _zoomRow(),
          if (_searching)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(
                child: CircularProgressIndicator(
                  color: FMagnitTheme.accent,
                ),
              ),
            ),
          if (_searchError != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                _searchError!,
                style: const TextStyle(color: Colors.red, fontSize: 13),
              ),
            ),
          for (final r in _results)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
              child: Container(
                decoration: BoxDecoration(
                  color: FMagnitTheme.card,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: ListTile(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                  title: Text(
                    r.displayName,
                    style: const TextStyle(
                      color: FMagnitTheme.textPrimary,
                      fontSize: 13,
                    ),
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: const Icon(Icons.download,
                      color: FMagnitTheme.accent),
                  onTap: () => _confirmDownload(r),
                ),
              ),
            ),
          const SizedBox(height: 12),
          _progressCard(),
          if (_regions.isNotEmpty) ...[
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Text(
                'Скачанные регионы',
                style: TextStyle(
                  color: FMagnitTheme.textPrimary,
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                ),
              ),
            ),
            for (final r in _regions) _regionTile(r),
          ],
        ],
      ),
    );
  }
}