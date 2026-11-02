import 'package:dream_cast/features/player/data/player_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/features/releases/presentation/release_detail_screen.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/episode_list_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _release = DreamRelease(
  id: 1,
  title: 'Release',
  originalTitle: '',
  url: '/home/release/1',
);
const _episode = DreamEpisode(
  id: '1',
  releaseId: 1,
  ordinal: 1,
  title: 'Episode',
  file: 'https://example.com/video.m3u8',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final embedded in [false, true]) {
    testWidgets(
      'online episodes preserve progress and quality; embedded=$embedded',
      (tester) async {
        SharedPreferences.setMockInitialValues({});
        final preferences = await SharedPreferences.getInstance();
        final detail = DreamReleaseDetail(
          release: _release,
          title: 'Release',
          description: null,
          thumbnailUrl: null,
          playerPayload: '',
          playerScriptUrl: '',
          fetchedAt: DateTime.now(),
        );
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              sharedPreferencesProvider.overrideWithValue(preferences),
              releaseDetailProvider(_release).overrideWith(
                (ref) async => DreamData(value: detail, isStale: false),
              ),
              releaseEpisodesProvider(detail).overrideWith(
                (ref) async =>
                    const DreamData(value: [_episode], isStale: true),
              ),
              episodeStreamsProvider(_episode).overrideWith(
                (ref) async => DreamData(
                  value: [
                    DreamStream(
                      id: 'stream',
                      releaseId: 1,
                      episodeId: '1',
                      url: Uri.parse(_episode.file),
                      type: DreamStreamType.hls,
                      quality: 720,
                    ),
                  ],
                  isStale: false,
                ),
              ),
              episodeWatchEntryProvider((
                release: _release,
                episode: _episode,
              )).overrideWith(
                (ref) => Stream.value(
                  ContinueWatchingItem(
                    releaseId: '1',
                    episodeId: '1',
                    releaseTitle: 'Release',
                    episodeTitle: 'Episode',
                    episodeOrdinal: 1,
                    position: const Duration(minutes: 6, seconds: 37),
                    duration: const Duration(minutes: 20),
                    updatedAt: DateTime.now(),
                    isWatched: false,
                  ),
                ),
              ),
            ],
            child: MaterialApp(
              home: embedded
                  ? const ReleaseDetailScreen(release: _release)
                  : EpisodeListScreen(detail: detail),
            ),
          ),
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        if (embedded) {
          await tester.tap(find.text('Смотреть'));
          await tester.pumpAndSettle();
          for (final quality in ['Auto', '480p', '720p', '1080p']) {
            expect(find.text(quality), findsOneWidget);
          }
        }
        expect(find.byTooltip('Смотреть'), findsOneWidget);
        expect(find.byTooltip('Скачать'), findsNothing);
        expect(find.byIcon(Icons.download_outlined), findsNothing);
        expect(find.text('Остановились на 06:37'), findsOneWidget);
        await tester.tap(find.byTooltip('Смотреть'));
        await tester.pumpAndSettle();
        expect(find.text('HLS • 720p'), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }
}
