import 'package:flutter/material.dart';

import 'ui/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FMagnitApp());
}

class FMagnitApp extends StatelessWidget {
  const FMagnitApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'fmagnit',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}