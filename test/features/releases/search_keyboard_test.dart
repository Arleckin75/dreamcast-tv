import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/app/theme/app_theme.dart';
import 'package:dream_cast/core/settings/app_settings.dart';
import 'package:dream_cast/features/releases/presentation/search_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  for (final dark in [false, true]) {
    testWidgets(
      'search uses one standard editable field; dark=$dark',
      (tester) async {
        SharedPreferences.setMockInitialValues({});
        final prefs = await SharedPreferences.getInstance();
        var nativeEditorCalls = 0;
        const nativeChannel = MethodChannel('dream_cast/tv_search_input');
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          nativeChannel,
          (call) async {
            nativeEditorCalls++;
            return null;
          },
        );
        addTearDown(
          () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            nativeChannel,
            null,
          ),
        );
        final themes = AppTheme.build(
          settings: const AppSettings(
            themeMode: ThemeMode.dark,
            accent: AppAccent.blue,
            useDynamicColor: false,
            onboardingCompleted: true,
          ),
          lightDynamic: null,
          darkDynamic: null,
        );
        await tester.pumpWidget(
          ProviderScope(
            overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
            child: MaterialApp(
              theme: dark ? themes.dark : themes.light,
              home: const SearchScreen(),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsOneWidget);
        expect(
          tester.widget<TextField>(find.byType(TextField)).readOnly,
          isFalse,
        );
        expect(find.byTooltip('Открыть клавиатуру'), findsNothing);
        expect(tester.testTextInput.isVisible, isTrue);
        await tester.enterText(find.byType(TextField), 'x');
        await tester.pumpAndSettle();
        tester.testTextInput.hide();
        await tester.tap(find.byType(TextField));
        await tester.pumpAndSettle();
        expect(tester.testTextInput.isVisible, isTrue);
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          'x',
        );
        expect(nativeEditorCalls, 0);
        await tester.pumpWidget(const SizedBox());
        expect(tester.takeException(), isNull);
      },
      variant: TargetPlatformVariant.only(TargetPlatform.android),
    );
  }
}
