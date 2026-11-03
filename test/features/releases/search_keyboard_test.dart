import 'dart:async';

import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/app/theme/app_theme.dart';
import 'package:dream_cast/core/settings/app_settings.dart';
import 'package:dream_cast/core/settings/player_build.dart';
import 'package:dream_cast/features/releases/presentation/widgets/tv_search_field.dart';
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
        int? platformId;
        final inlineCalls = <MethodCall>[];
        var createdViews = 0;
        if (softwarePlayerBuild) {
          tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            SystemChannels.platform_views,
            (call) async {
              if (call.method == 'create') {
                createdViews++;
                platformId = (call.arguments as Map)['id'] as int;
                tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
                  MethodChannel('dream_cast/tv_search_field/$platformId'),
                  (call) async {
                    inlineCalls.add(call);
                    return null;
                  },
                );
              }
              return null;
            },
          );
          addTearDown(() {
            tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
              SystemChannels.platform_views,
              null,
            );
            if (platformId != null) {
              tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
                MethodChannel('dream_cast/tv_search_field/$platformId'),
                null,
              );
            }
          });
        }
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
        if (softwarePlayerBuild) {
          expect(createdViews, 1);
          expect(find.byType(TvSearchField), findsOneWidget);
          expect(find.byType(TextField), findsNothing);
          expect(find.byType(Dialog), findsNothing);
          expect(inlineCalls.any((call) => call.method == 'focus'), isTrue);
          // These keys must leave Flutter unhandled so Android/Gboard receives them.
          expect(
            await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight),
            isFalse,
          );
          final reply = Completer<void>();
          tester.binding.channelBuffers.push(
            'dream_cast/tv_search_field/$platformId',
            const StandardMethodCodec().encodeMethodCall(
              const MethodCall('changed', 'x'),
            ),
            (_) => reply.complete(),
          );
          await tester.pump();
          await reply.future;
          await tester.pumpAndSettle();
          expect(
            tester
                .widget<TvSearchField>(find.byType(TvSearchField))
                .controller
                .text,
            'x',
          );
          await tester.tap(find.byTooltip('Очистить'));
          await tester.pumpAndSettle();
          expect(
            inlineCalls.any(
              (call) => call.method == 'text' && call.arguments == '',
            ),
            isTrue,
          );
          expect(createdViews, 1);
          expect(nativeEditorCalls, 0);
          await tester.pumpWidget(const SizedBox());
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
          return;
        }
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
