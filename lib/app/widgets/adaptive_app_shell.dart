import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// Persistent TV navigation, independent of the window width.
class AdaptiveAppShell extends StatefulWidget {
  const AdaptiveAppShell({required this.navigationShell, super.key});
  final StatefulNavigationShell navigationShell;

  @override
  State<AdaptiveAppShell> createState() => _AdaptiveAppShellState();
}

class _AdaptiveAppShellState extends State<AdaptiveAppShell> {
  final _tabs = List.generate(
    4,
    (index) => FocusNode(debugLabel: 'tv-tab-$index'),
  );
  StatefulNavigationShell get navigationShell => widget.navigationShell;
  late int _selectedIndex;

  @override
  void initState() {
    super.initState();
    _selectedIndex = navigationShell.currentIndex;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _tabs[navigationShell.currentIndex].requestFocus();
    });
  }

  @override
  void didUpdateWidget(covariant AdaptiveAppShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_selectedIndex != navigationShell.currentIndex) {
      _selectedIndex = navigationShell.currentIndex;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _tabs[_selectedIndex].requestFocus();
      });
    }
  }

  @override
  void dispose() {
    for (final tab in _tabs) {
      tab.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const destinations = [
      (Icons.home_outlined, 'Главная'),
      (Icons.collections_bookmark_outlined, 'Библиотека'),
      (Icons.calendar_month_outlined, 'Календарь'),
      (Icons.settings_outlined, 'Настройки'),
    ];
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      body: SafeArea(
        minimum: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
        child: Column(
          children: [
            FocusTraversalGroup(
              child: Row(
                children: [
                  for (var index = 0; index < destinations.length; index++)
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        child: TextButton.icon(
                          focusNode: _tabs[index],
                          autofocus: index == navigationShell.currentIndex,
                          style: TextButton.styleFrom(
                            minimumSize: const Size(0, 52),
                            backgroundColor:
                                index == navigationShell.currentIndex
                                ? colors.secondaryContainer
                                : null,
                            foregroundColor:
                                index == navigationShell.currentIndex
                                ? colors.onSecondaryContainer
                                : colors.onSurface,
                          ),
                          onPressed: () => navigationShell.goBranch(index),
                          icon: Icon(destinations[index].$1),
                          label: Text(
                            destinations[index].$2,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Expanded(child: navigationShell),
          ],
        ),
      ),
    );
  }
}
