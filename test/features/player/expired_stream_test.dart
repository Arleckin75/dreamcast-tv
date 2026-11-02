import 'package:dio/dio.dart';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/features/player/data/playback_repository.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/releases/data/dream_cast_api.dart';
import 'package:dream_cast/features/releases/data/dream_cast_parser_service.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late AppDatabase database;
  setUp(() => database = AppDatabase(NativeDatabase.memory()));
  tearDown(() => database.close());
  const release = DreamRelease(
    id: 1,
    title: 'Test',
    originalTitle: '',
    url: '/home/release/1',
  );
  const episode = DreamEpisode(
    id: 'ep-1',
    releaseId: 1,
    ordinal: 1,
    title: 'Episode',
    file: 'https://example.com/fresh.m3u8',
  );

  test(
    'saved expired playback session cannot be reopened as a valid request',
    () async {
      final repository = PlaybackRepository(database);
      await repository.saveDreamStreamSession(
        release: release,
        episode: episode,
        stream: DreamStream(
          id: 'stream',
          releaseId: 1,
          episodeId: 'ep-1',
          url: Uri.parse('https://example.com/expired.m3u8'),
          type: DreamStreamType.hls,
          quality: 720,
          expiresAt: DateTime.now().subtract(const Duration(seconds: 1)),
        ),
      );
      final request = await repository.restorePlaybackRequest(
        ContinueWatchingItem(
          releaseId: '1',
          episodeId: 'ep-1',
          releaseTitle: 'Test',
          episodeTitle: 'Episode',
          episodeOrdinal: 1,
          position: const Duration(seconds: 20),
          duration: const Duration(minutes: 20),
          updatedAt: DateTime.now(),
          isWatched: false,
        ),
      );
      expect(request, isNull);
    },
  );

  test(
    'forever cache retention does not make signed streams immortal',
    () async {
      final cache = CacheRepository(
        database,
        retention: CacheRetentionOption.forever,
      );
      await cache.putJson('dreamcast:streams:1:ep-1', {
        'streams': [
          {
            'id': 'old',
            'releaseId': 1,
            'episodeId': 'ep-1',
            'url': 'https://example.com/expired.m3u8',
            'type': 'hls',
            'quality': 720,
            'headers': <String, String>{},
            'expiresAt': DateTime.now()
                .subtract(const Duration(seconds: 1))
                .toIso8601String(),
          },
        ],
      });
      final repository = ReleaseRepository(
        api: DreamCastApi(Dio()),
        cache: cache,
        parser: const DreamCastParserService(),
      );
      final streams = await repository.getStreams(episode);
      expect(streams.value.single.url.path, '/fresh.m3u8');
    },
  );
}
