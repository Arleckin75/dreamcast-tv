import 'dart:async';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/core/settings/player_build.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/search_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  if (softwarePlayerBuild) {
    testWidgets(
      'software search types through Android editor and retains query',
      (tester) async {
        SharedPreferences.setMockInitialValues({});
        final prefs = await SharedPreferences.getInstance();
        final calls = <MethodCall>[];
        final replies = <Completer<Map<String, dynamic>?>>[];
        const channel = MethodChannel('dream_cast/tv_search_input');
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          (call) {
            calls.add(call);
            final reply = Completer<Map<String, dynamic>?>();
            replies.add(reply);
            return reply.future;
          },
        );
        addTearDown(
          () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            channel,
            null,
          ),
        );
        await tester.pumpWidget(
          ProviderScope(
            overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
            child: const MaterialApp(home: SearchScreen()),
          ),
        );
        await tester.pumpAndSettle();
        expect(calls.length, 1);
        expect(calls.first.method, 'edit');
        expect(calls.first.arguments['text'], '');
        expect(
          tester.widget<TextField>(find.byType(TextField)).readOnly,
          isTrue,
        );
        expect(tester.testTextInput.isVisible, isFalse);
        // Dismissing native input keeps what was typed without submitting a search.
        replies.first.complete({'text': 'x', 'submitted': false});
        await tester.pumpAndSettle();
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          'x',
        );
        await tester.tap(find.byTooltip('Открыть клавиатуру'));
        await tester.pumpAndSettle();
        expect(calls.length, 2);
        expect(calls.last.arguments['text'], 'x');
        expect(tester.testTextInput.isVisible, isFalse);
        // Late native completion after leaving the screen must be ignored safely.
        await tester.pumpWidget(const SizedBox());
        replies.last.complete({'text': 'y', 'submitted': false});
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      },
      variant: TargetPlatformVariant.only(TargetPlatform.android),
    );
  }
  for (final hasResults in softwarePlayerBuild ? <bool>[] : [false, true]) {
    testWidgets(
      'TV search keyboard reopens; results=$hasResults',
      (tester) async {
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              searchReleasesProvider.overrideWithBuild(
                (ref, notifier) async => SearchState(
                  query: hasResults ? 'TV' : '',
                  history: const [],
                  items: hasResults
                      ? const [
                          DreamRelease(
                            id: 1,
                            title: 'TV',
                            originalTitle: '',
                            url: '/release/1',
                          ),
                        ]
                      : const [],
                  isSearching: false,
                  isStale: false,
                ),
              ),
            ],
            child: const MaterialApp(home: SearchScreen()),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.testTextInput.isVisible, isTrue);
        for (final key in [
          LogicalKeyboardKey.select,
          LogicalKeyboardKey.enter,
          LogicalKeyboardKey.numpadEnter,
        ]) {
          tester.testTextInput.hide();
          expect(tester.testTextInput.isVisible, isFalse);
          await tester.sendKeyEvent(key);
          await tester.pumpAndSettle();
          expect(tester.testTextInput.isVisible, isTrue, reason: '$key');
          expect(
            FocusManager.instance.primaryFocus?.debugLabel,
            'search-input',
          );
        }
        if (hasResults) {
          tester.view.viewInsets = const FakeViewPadding(bottom: 160);
          addTearDown(tester.view.resetViewInsets);
          await tester.pump();
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
          await tester.pump();
          expect(
            FocusManager.instance.primaryFocus?.debugLabel,
            'search-input',
          );
          tester.view.resetViewInsets();
          tester.testTextInput.hide();
          await tester.pump();
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
          await tester.pump();
          expect(
            FocusManager.instance.primaryFocus?.debugLabel,
            'first-search-result',
          );
          await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
          await tester.pumpAndSettle();
          expect(
            FocusManager.instance.primaryFocus?.debugLabel,
            'search-input',
          );
          expect(tester.testTextInput.isVisible, isTrue);
        }
        tester.testTextInput.hide();
        await tester.tap(find.byTooltip('Открыть клавиатуру'));
        await tester.pumpAndSettle();
        expect(tester.testTextInput.isVisible, isTrue);
        await tester.pumpWidget(const SizedBox());
        expect(tester.takeException(), isNull);
      },
      variant: TargetPlatformVariant.only(TargetPlatform.android),
    );
  }
}
