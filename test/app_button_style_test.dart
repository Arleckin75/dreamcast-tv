import 'package:dream_cast/app/theme/app_theme.dart';
import 'package:dream_cast/core/settings/app_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // Exercise rendered controls, including variants with their own filled defaults.
  for (final brightness in Brightness.values) {
    testWidgets('buttons share contour focus in $brightness', (tester) async {
      final bundle = AppTheme.build(
        settings: const AppSettings(
          themeMode: ThemeMode.dark,
          accent: AppAccent.green,
          useDynamicColor: false,
          onboardingCompleted: true,
        ),
        lightDynamic: null,
        darkDynamic: null,
      );
      final theme = brightness == Brightness.dark ? bundle.dark : bundle.light;
      final states = WidgetStatesController();
      addTearDown(states.dispose);
      var enabled = true;
      Widget build() => MaterialApp(
        theme: theme,
        home: Scaffold(
          body: Wrap(
            children: [
              TextButton(
                key: const Key('text'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                child: const Text('Text'),
              ),
              FilledButton(
                key: const Key('filled'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                child: const Text('Filled'),
              ),
              FilledButton.tonal(
                key: const Key('tonal'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                child: const Text('Tonal'),
              ),
              OutlinedButton(
                key: const Key('outlined'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                child: const Text('Outlined'),
              ),
              ElevatedButton(
                key: const Key('elevated'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                child: const Text('Elevated'),
              ),
              IconButton.filled(
                key: const Key('icon'),
                statesController: states,
                onPressed: enabled ? () {} : null,
                icon: const Icon(Icons.play_arrow),
              ),
            ],
          ),
        ),
      );
      Material surface(String key) => tester.widget<Material>(
        find
            .descendant(
              of: find.byKey(Key(key)),
              matching: find.byType(Material),
            )
            .first,
      );
      const keys = ['text', 'filled', 'tonal', 'outlined', 'elevated', 'icon'];
      await tester.pumpWidget(build());
      for (final key in keys) {
        expect(surface(key).color, Colors.transparent, reason: key);
      }
      states.update(WidgetState.focused, true);
      await tester.pumpAndSettle();
      for (final key in keys) {
        final shape = surface(key).shape! as OutlinedBorder;
        expect(shape.side.color, theme.colorScheme.primary, reason: key);
        expect(shape.side.width, 3, reason: key);
        expect(surface(key).color, Colors.transparent, reason: key);
      }
      enabled = false;
      await tester.pumpWidget(build());
      await tester.pumpAndSettle();
      for (final key in keys) {
        final shape = surface(key).shape! as OutlinedBorder;
        expect(shape.side.color, Colors.transparent, reason: key);
      }
      await tester.pumpWidget(const SizedBox());
    });
  }
}
