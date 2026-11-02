import 'package:dream_cast/features/player/presentation/episode_completion_dialog.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final scenario in [
    'auto',
    'cancel',
    'back',
    'background',
    'last',
    'dispose',
  ]) {
    testWidgets('episode completion: $scenario', (tester) async {
      EpisodeCompletionAction? action;
      var calls = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => FilledButton(
                onPressed: () async {
                  action = await showDialog<EpisodeCompletionAction>(
                    context: context,
                    barrierDismissible: false,
                    builder: (_) => EpisodeCompletionDialog(
                      nextTitle: scenario == 'last' ? null : 'Серия 2',
                    ),
                  );
                  calls++;
                },
                child: const Text('Завершить'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Завершить'));
      await tester.pumpAndSettle();
      if (scenario == 'auto') {
        await tester.pump(const Duration(seconds: 9));
        expect(action, isNull);
        expect(find.textContaining('Переход через 1 с'), findsOneWidget);
        await tester.pump(const Duration(seconds: 1));
        await tester.pumpAndSettle();
        expect(action, EpisodeCompletionAction.next);
        expect(calls, 1);
      } else if (scenario == 'last') {
        await tester.pump(const Duration(minutes: 1));
        expect(action, isNull);
        expect(find.text('Следующая серия'), findsNothing);
        await tester.tap(find.text('К тайтлу'));
        await tester.pumpAndSettle();
        expect(action, EpisodeCompletionAction.exit);
      } else if (scenario == 'dispose') {
        await tester.pumpWidget(const SizedBox());
        await tester.pump(const Duration(seconds: 20));
        expect(tester.takeException(), isNull);
      } else {
        if (scenario == 'cancel') {
          await tester.tap(find.text('Отменить переход'));
        } else if (scenario == 'back') {
          await tester.binding.handlePopRoute();
        } else {
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.inactive,
          );
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
        }
        await tester.pump();
        await tester.pump(const Duration(seconds: 20));
        expect(action, isNull);
        expect(find.textContaining('Автопереход отменён.'), findsOneWidget);
        if (scenario == 'back') {
          await tester.binding.handlePopRoute();
          await tester.pumpAndSettle();
          expect(action, EpisodeCompletionAction.exit);
        } else {
          await tester.tap(find.text('Следующая серия'));
          await tester.pumpAndSettle();
          expect(action, EpisodeCompletionAction.next);
        }
        expect(calls, 1);
      }
      await tester.pumpWidget(const SizedBox());
    });
  }
}
