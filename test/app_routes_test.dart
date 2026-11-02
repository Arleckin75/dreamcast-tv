import 'package:dream_cast/app/router/app_router.dart';
import 'package:dream_cast/features/downloads/data/download_providers.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/release_detail_screen.dart';
import 'package:dream_cast/features/releases/presentation/episode_list_screen.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _release = DreamRelease(
  id: 9,
  title: 'Route release',
  originalTitle: '',
  url: '/home/release/9',
);
final _detail = DreamReleaseDetail(
  release: _release,
  title: 'Route release',
  description: null,
  thumbnailUrl: null,
  playerPayload: 'test',
  playerScriptUrl: 'https://example.com/player.js',
  fetchedAt: DateTime(2026),
);

void main() {
  testWidgets('release route restores its data without GoRouter extra', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final container = ProviderContainer(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(preferences),
        releaseByIdProvider((
          id: 9,
          seed: null,
        )).overrideWith((ref) async => _release),
        releaseDetailProvider(_release).overrideWith(
          (ref) async => DreamData(value: _detail, isStale: false),
        ),
      ],
    );
    addTearDown(container.dispose);
    final router = container.read(appRouterProvider)..go('/release/9');
    addTearDown(router.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(ReleaseDetailScreen), findsOneWidget);
    expect(find.text('Не удалось открыть'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('episodes route restores its release by id', (tester) async {
    final container = ProviderContainer(
      overrides: [
        downloadedEpisodesStreamProvider.overrideWith(
          (ref) => Stream.value([]),
        ),
        releaseDetailByIdProvider((id: 9, seed: null)).overrideWith(
          (ref) async => DreamData(value: _detail, isStale: false),
        ),
        releaseEpisodesProvider(_detail).overrideWith(
          (ref) async =>
              const DreamData(value: <DreamEpisode>[], isStale: false),
        ),
      ],
    );
    addTearDown(container.dispose);
    final router = container.read(appRouterProvider)..go('/release/9/episodes');
    addTearDown(router.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(EpisodeListScreen), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('watch route requests fresh playback data and offers retry', (
    tester,
  ) async {
    var requests = 0;
    final container = ProviderContainer(
      overrides: [
        watchPlaybackRequestProvider((
          releaseId: 9,
          episodeId: 'ep-1',
        )).overrideWith((ref) async {
          requests++;
          throw StateError('Stream unavailable');
        }),
      ],
    );
    addTearDown(container.dispose);
    final router = container.read(appRouterProvider)..go('/watch/9/ep-1');
    addTearDown(router.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    expect(requests, 1);
    expect(find.text('Не удалось открыть'), findsNothing);
    expect(find.text('Повторить'), findsOneWidget);
    await tester.tap(find.text('Повторить'));
    await tester.pumpAndSettle();
    expect(requests, 2);
    await tester.pumpWidget(const SizedBox());
  });
}
