import 'package:flutter/material.dart';

import '../main.dart';

enum SortMode {
  byDate,
  byName,
  byGroup,
}

extension SortModeLabel on SortMode {
  String get label {
    switch (this) {
      case SortMode.byDate:
        return 'По дате';
      case SortMode.byName:
        return 'По имени';
      case SortMode.byGroup:
        return 'По группе';
    }
  }

  IconData get icon {
    switch (this) {
      case SortMode.byDate:
        return Icons.schedule;
      case SortMode.byName:
        return Icons.sort_by_alpha;
      case SortMode.byGroup:
        return Icons.folder_outlined;
    }
  }
}

Future<SortMode?> showSortMenu(BuildContext context, SortMode current) async {
  return showModalBottomSheet<SortMode>(
    context: context,
    builder: (ctx) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 16),
            child: Text(
              'Сортировка',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 16,
                color: FMagnitTheme.textPrimary,
              ),
            ),
          ),
          for (final m in SortMode.values)
            ListTile(
              leading: Icon(
                m.icon,
                color: m == current
                    ? FMagnitTheme.accent
                    : FMagnitTheme.textMuted,
              ),
              title: Text(
                m.label,
                style: TextStyle(
                  color: m == current
                      ? FMagnitTheme.accent
                      : FMagnitTheme.textPrimary,
                  fontWeight:
                      m == current ? FontWeight.bold : FontWeight.normal,
                ),
              ),
              trailing: m == current
                  ? const Icon(Icons.check, color: FMagnitTheme.accent)
                  : null,
              onTap: () => Navigator.of(ctx).pop(m),
            ),
          const SizedBox(height: 8),
        ],
      ),
    ),
  );
}