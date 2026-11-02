import 'package:dio/dio.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/settings/player_build.dart';
import 'package:dream_cast/features/downloads/data/download_providers.dart';
import 'package:dream_cast/features/downloads/data/download_service.dart';
import 'package:dream_cast/features/player/data/player_providers.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/episode_list_screen.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

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

  testWidgets(
    'software flavor exposes online playback without download actions',
    (tester) async {
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
            releaseEpisodesProvider(detail).overrideWith(
              (ref) async => const DreamData(value: [_episode], isStale: false),
            ),
            downloadedEpisodesStreamProvider.overrideWith(
              (ref) => Stream.value([]),
            ),
            downloadedEpisodeStreamProvider((
              releaseId: 1,
              episodeId: '1',
            )).overrideWith((ref) => Stream.value(null)),
            episodeWatchEntryProvider((
              release: _release,
              episode: _episode,
            )).overrideWith((ref) => Stream.value(null)),
          ],
          child: MaterialApp(home: EpisodeListScreen(detail: detail)),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byTooltip('Смотреть'), findsOneWidget);
      expect(find.byTooltip('Скачать'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
    skip: !softwarePlayerBuild,
  );

  test(
    'software flavor refuses download requests at the service boundary',
    () async {
      final database = AppDatabase(NativeDatabase.memory());
      addTearDown(database.close);
      final service = DownloadService(
        database,
        Dio(),
        useForegroundTask: false,
      );
      expect(
        () => service.startDownload(
          release: _release,
          episode: _episode,
          stream: DreamStream(
            id: '1',
            releaseId: 1,
            episodeId: '1',
            url: Uri.parse(_episode.file),
            type: DreamStreamType.hls,
            quality: 720,
          ),
        ),
        throwsStateError,
      );
    },
    skip: !softwarePlayerBuild,
  );
}
