import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// TV-compatible navigation: the rail can be entered with D-pad Left from
/// the first content column and left with D-pad Right.
class AdaptiveAppShell extends StatefulWidget {
  const AdaptiveAppShell({required this.navigationShell, super.key});

  final StatefulNavigationShell navigationShell;

  @override
  State<AdaptiveAppShell> createState() => _AdaptiveAppShellState();
}

class _AdaptiveAppShellState extends State<AdaptiveAppShell> {
  void _goBranch(int index) {
    widget.navigationShell.goBranch(
      index,
      initialLocation: index == widget.navigationShell.currentIndex,
    );
  }

  @override
  Widget build(BuildContext context) {
    // Television navigation lives in the Home AppBar. The narrow-screen
    // bottom bar is retained for phone compatibility.
    if (MediaQuery.sizeOf(context).width >= 720) {
      return Scaffold(body: widget.navigationShell);
    }
    return Scaffold(
      body: widget.navigationShell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: widget.navigationShell.currentIndex,
        onDestinationSelected: _goBranch,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined), label: 'Главная'),
          NavigationDestination(icon: Icon(Icons.collections_bookmark_outlined), label: 'Библиотека'),
          NavigationDestination(icon: Icon(Icons.calendar_month_outlined), label: 'Календарь'),
          NavigationDestination(icon: Icon(Icons.settings_outlined), label: 'Настройки'),
        ],
      ),
    );
  }
}
