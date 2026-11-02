import 'dart:async';

import 'package:dream_cast/app/bootstrap/app_bootstrap.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/app/router/app_router.dart';
import 'package:dream_cast/app/widgets/adaptive_app_shell.dart';
import 'package:dream_cast/app/widgets/tv_dialog.dart';
import 'package:dream_cast/app/widgets/tv_refresh.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/database/database_providers.dart';
import 'package:dream_cast/features/home/presentation/home_screen.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/release_list_state.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _EmptyReleases extends LatestReleasesController {
  @override
  Future<ReleaseListState> build() async => const ReleaseListState.empty();
}

void main() {
  for (final size in [const Size(640, 360), const Size(960, 540)]) {
    testWidgets('home actions share one row and work with D-pad at $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var activated = false;
      SharedPreferences.setMockInitialValues({});
      final preferences = await SharedPreferences.getInstance();
      final router = GoRouter(
        initialLocation: '/home',
        routes: [
          StatefulShellRoute.indexedStack(
            builder: (context, state, shell) =>
                AdaptiveAppShell(navigationShell: shell),
            branches: [
              for (final path in [
                '/home',
                '/library',
                '/schedule',
                '/settings',
              ])
                StatefulShellBranch(
                  routes: [
                    GoRoute(
                      path: path,
                      builder: (context, state) => path == '/home'
                          ? const HomeScreen()
                          : Scaffold(
                              body: Center(
                                child: FilledButton(
                                  autofocus: true,
                                  onPressed: () => activated = true,
                                  child: Text('Контент $path'),
                                ),
                              ),
                            ),
                    ),
                  ],
                ),
            ],
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(preferences),
            latestReleasesProvider.overrideWith(_EmptyReleases.new),
          ],
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsNothing);
      expect(find.byType(AppBar), findsOneWidget);
      expect(find.text('Главная'), findsNothing);
      expect(find.text('Обновить'), findsNothing);
      const labels = [
        'Поиск',
        'Уведомления',
        'Библиотека',
        'Календарь',
        'Настройки',
      ];
      final positions = labels
          .map((label) => tester.getCenter(find.byTooltip(label)))
          .toList();
      for (var i = 1; i < positions.length; i++) {
        expect(positions[i].dy, positions.first.dy);
        expect(positions[i].dx, greaterThan(positions[i - 1].dx));
      }
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.select);
      await tester.pumpAndSettle();
      expect(router.routeInformationProvider.value.uri.path, '/library');
      await tester.sendKeyEvent(LogicalKeyboardKey.select);
      await tester.pump();
      expect(activated, isTrue);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets('a TV dialog focuses a choice and accepts D-pad selection', (
    tester,
  ) async {
    String? choice;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => FilledButton(
              autofocus: true,
              onPressed: () async {
                choice = await showTvDialog<String>(
                  context: context,
                  builder: (context) => ListView(
                    shrinkWrap: true,
                    children: [
                      for (final value in ['Первый', 'Второй'])
                        ListTile(
                          title: Text(value),
                          onTap: () => Navigator.pop(context, value),
                        ),
                    ],
                  ),
                );
              },
              child: const Text('Выбрать'),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.select);
    await tester.pumpAndSettle();
    expect(find.byType(Dialog), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.select);
    await tester.pumpAndSettle();
    expect(choice, 'Второй');
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('refresh is available using OK without a swipe', (tester) async {
    var refreshes = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: TvRefresh(
            onRefresh: () async {
              refreshes++;
            },
            child: const Text('Каталог'),
          ),
        ),
      ),
    );
    Focus.of(tester.element(find.text('Обновить'))).requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.select);
    await tester.pumpAndSettle();
    expect(refreshes, 1);
  });

  testWidgets('long text can be scrolled with D-pad', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: 180,
            child: TvScrollableText(
              List.generate(100, (index) => 'Строка $index').join('\n'),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pump();
    await tester.pumpAndSettle();
    final scrollable = tester.state<ScrollableState>(find.byType(Scrollable));
    expect(scrollable.position.pixels, greaterThan(0));
  });

  testWidgets('Back on the root returns to the TV launcher', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final database = AppDatabase(NativeDatabase.memory());
    addTearDown(database.close);
    final router = GoRouter(
      initialLocation: '/home',
      routes: [
        GoRoute(
          path: '/home',
          builder: (context, state) => const Scaffold(body: Text('Главная')),
        ),
      ],
    );
    addTearDown(router.dispose);
    var exited = false;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'SystemNavigator.pop') exited = true;
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appRouterProvider.overrideWithValue(router),
          sharedPreferencesProvider.overrideWithValue(preferences),
          appDatabaseProvider.overrideWithValue(database),
        ],
        child: DreamCastApp(
          bootstrap: AppBootstrapResult(
            preferences: preferences,
            database: database,
          ),
        ),
      ),
    );
    await tester.pump(const Duration(seconds: 1));
    Future<void> back() async {
      final reply = Completer<void>();
      tester.binding.channelBuffers.push(
        'dream_cast/tv_navigation',
        const StandardMethodCodec().encodeMethodCall(
          const MethodCall('shortBack'),
        ),
        (_) => reply.complete(),
      );
      await tester.pump();
      await reply.future;
    }

    tester.view.viewInsets = const FakeViewPadding(bottom: 100);
    addTearDown(tester.view.resetViewInsets);
    await back();
    expect(exited, isFalse);
    expect(
      tester.testTextInput.log.any((call) => call.method == 'TextInput.hide'),
      isTrue,
    );
    tester.view.resetViewInsets();
    final dialog = showTvDialog<void>(
      context: tester.element(find.text('Главная')),
      builder: (context) => FilledButton(
        autofocus: true,
        onPressed: () => Navigator.pop(context),
        child: const Text('Закрыть'),
      ),
    );
    await tester.pumpAndSettle();
    await back();
    await tester.pumpAndSettle();
    await dialog;
    expect(find.byType(Dialog), findsNothing);
    expect(exited, isFalse);
    await back();
    expect(exited, isTrue);
    await tester.pumpWidget(const SizedBox());
  });
}
