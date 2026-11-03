import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/search_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final hasResults in [false, true]) {
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
