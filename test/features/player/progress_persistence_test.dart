import 'package:dream_cast/core/database/app_database.dart'
    hide PlaybackPosition;
import 'package:dream_cast/features/player/data/playback_repository.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late AppDatabase database;
  late PlaybackRepository repository;
  const release = DreamRelease(
    id: 1,
    title: 'TV',
    originalTitle: '',
    url: '/1',
  );
  const episode = DreamEpisode(
    id: 'ep',
    releaseId: 1,
    ordinal: 1,
    title: 'Episode',
    file: 'https://example.com/video.m3u8',
  );
  setUp(() {
    database = AppDatabase(NativeDatabase.memory());
    repository = PlaybackRepository(database);
  });
  tearDown(() => database.close());

  test(
    'position and continue watching agree; completed episode restarts',
    () async {
      await repository.saveWatchProgress(
        release: release,
        episode: episode,
        position: const Duration(minutes: 21),
        duration: const Duration(minutes: 20),
      );
      final position = (await repository.getPosition(
        releaseId: '1',
        episodeId: 'ep',
      ))!;
      final entry = (await repository.getEpisodeWatchEntry(
        release: release,
        episode: episode,
      ))!;
      expect(position.position, const Duration(minutes: 20));
      expect(entry.position, position.position);
      expect(entry.isWatched, isTrue);
      expect(position.resumePosition, Duration.zero);
      await repository.saveWatchProgress(
        release: release,
        episode: episode,
        position: const Duration(minutes: 3),
        duration: const Duration(minutes: 20),
      );
      expect(
        (await repository.getPosition(
          releaseId: '1',
          episodeId: 'ep',
        ))!.resumePosition,
        const Duration(minutes: 3),
      );
    },
  );

  test('invalid negative progress is normalized', () async {
    await repository.saveWatchProgress(
      release: release,
      episode: episode,
      position: const Duration(seconds: -10),
      duration: Duration.zero,
    );
    final position = (await repository.getPosition(
      releaseId: '1',
      episodeId: 'ep',
    ))!;
    expect(position.position, Duration.zero);
    expect(position.duration, isNull);
  });

  test('native completion works without a reported duration', () async {
    await repository.saveWatchProgress(
      release: release,
      episode: episode,
      position: Duration.zero,
      duration: null,
      completed: true,
    );
    expect(
      (await repository.getEpisodeWatchEntry(
        release: release,
        episode: episode,
      ))!.isWatched,
      isTrue,
    );
  });

  test(
    'completing latest episode does not resurrect an older unfinished one',
    () async {
      await repository.saveWatchProgress(
        release: release,
        episode: episode,
        position: const Duration(minutes: 2),
        duration: const Duration(minutes: 20),
      );
      const next = DreamEpisode(
        id: 'ep2',
        releaseId: 1,
        ordinal: 2,
        title: 'Episode 2',
        file: 'https://example.com/2.m3u8',
      );
      await repository.saveWatchProgress(
        release: release,
        episode: next,
        position: const Duration(minutes: 20),
        duration: const Duration(minutes: 20),
      );
      expect(await repository.watchContinueWatching().first, isEmpty);
    },
  );

  test(
    'watch-entry failure rolls back position in the same transaction',
    () async {
      await database.customSelect('SELECT 1').get();
      await database.customStatement('''CREATE TRIGGER reject_watch_entry
      BEFORE INSERT ON watch_entries BEGIN SELECT RAISE(ABORT, 'test'); END''');
      await expectLater(
        repository.saveWatchProgress(
          release: release,
          episode: episode,
          position: const Duration(minutes: 2),
          duration: const Duration(minutes: 20),
        ),
        throwsA(anything),
      );
      expect(
        await repository.getPosition(releaseId: '1', episodeId: 'ep'),
        isNull,
      );
    },
  );
}
