import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// Keeps the branch navigators and TV safe margins without a separate menu.
class AdaptiveAppShell extends StatelessWidget {
  const AdaptiveAppShell({required this.navigationShell, super.key});

  final StatefulNavigationShell navigationShell;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      minimum: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      child: navigationShell,
    );
  }
}
