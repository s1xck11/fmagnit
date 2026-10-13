import 'package:flutter/material.dart';

import 'ui/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FMagnitApp());
}

class FMagnitTheme {
  static const Color bg = Color(0xFF0D0D0D);
  static const Color card = Color(0xFF1A1A1A);
  static const Color surface = Color(0xFF1A1A1A);
  static const Color textPrimary = Color(0xFFFFFFFF);
  static const Color textMuted = Color(0xFF9E9E9E);
  static const Color accent = Color(0xFFE30613);
  static const Color success = Color(0xFF00C853);
  static const Color inactive = Color(0xFF2A2A2A);
  static const Color divider = Color(0xFF2A2A2A);

  static ThemeData build() {
    const colorScheme = ColorScheme(
      brightness: Brightness.dark,
      primary: accent,
      onPrimary: Colors.white,
      secondary: accent,
      onSecondary: Colors.white,
      error: accent,
      onError: Colors.white,
      surface: surface,
      onSurface: textPrimary,
      background: bg,
      onBackground: textPrimary,
      outline: divider,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: bg,
      canvasColor: bg,
      dividerColor: divider,
      splashColor: Colors.white10,
      highlightColor: Colors.white10,
      appBarTheme: const AppBarTheme(
        backgroundColor: bg,
        foregroundColor: textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 20,
          fontWeight: FontWeight.bold,
        ),
        iconTheme: IconThemeData(color: textPrimary),
      ),
      cardTheme: CardTheme(
        color: card,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: accent,
          foregroundColor: Colors.white,
          disabledBackgroundColor: inactive,
          disabledForegroundColor: textMuted,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          textStyle: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.bold,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          side: const BorderSide(color: divider),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: textPrimary,
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: accent,
        foregroundColor: Colors.white,
        elevation: 0,
        highlightElevation: 0,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        hintStyle: const TextStyle(color: textMuted, fontSize: 14),
        labelStyle: const TextStyle(color: textMuted, fontSize: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: divider),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: divider),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: accent, width: 1.5),
        ),
      ),
      dialogTheme: DialogTheme(
        backgroundColor: card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
        ),
        titleTextStyle: const TextStyle(
          color: textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.bold,
        ),
        contentTextStyle: const TextStyle(
          color: textPrimary,
          fontSize: 14,
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: card,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        iconColor: textMuted,
        textColor: textPrimary,
      ),
      iconTheme: const IconThemeData(color: textPrimary),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: accent,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: card,
        contentTextStyle: const TextStyle(color: textPrimary),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: card,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
      ),
    );
  }
}

class FMagnitApp extends StatelessWidget {
  const FMagnitApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'fmagnit',
      debugShowCheckedModeBanner: false,
      theme: FMagnitTheme.build(),
      home: const HomePage(),
    );
  }
}