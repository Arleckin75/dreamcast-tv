import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/database/database_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/player/presentation/video_player_screen.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/data/dream_cast_api.dart';
import 'package:dream_cast/features/releases/data/dream_cast_parser_service.dart';
import 'package:dream_cast/features/releases/data/parsers/playerjs_playlist_decoder.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/data/release_providers.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

class _Player extends VideoPlayerPlatform {
  bool delayInitialization = false;
  bool injectable = false;
  final pendingEvents = StreamController<VideoEvent>.broadcast();
  final sources = <String>[];
  final creationStarted = Completer<void>();
  int disposed = 0;
  bool playing = false;
  Duration position = const Duration(seconds: 30);
  @override
  Future<void> init() async {}
  @override
  Future<int?> createWithOptions(VideoCreationOptions options) async {
    if (!creationStarted.isCompleted) creationStarted.complete();
    sources.add(options.dataSource.uri ?? '');
    return sources.length;
  }

  @override
  Stream<VideoEvent> videoEventsFor(int playerId) {
    final initialized = VideoEvent(
      eventType: VideoEventType.initialized,
      duration: const Duration(minutes: 20),
      size: const Size(1920, 1080),
    );
    if (injectable) scheduleMicrotask(() => pendingEvents.add(initialized));
    return delayInitialization || injectable
        ? pendingEvents.stream
        : Stream.value(initialized);
  }

  @override
  Widget buildViewWithOptions(VideoViewOptions options) => const SizedBox();
  @override
  Future<void> dispose(int playerId) async {
    disposed++;
  }

  @override
  Future<void> play(int playerId) async {
    playing = true;
  }

  @override
  Future<void> pause(int playerId) async {
    playing = false;
  }

  @override
  Future<void> setVolume(int playerId, double volume) async {}
  @override
  Future<void> setLooping(int playerId, bool looping) async {}
  @override
  Future<void> setMixWithOthers(bool mixWithOthers) async {}
  @override
  Future<void> setPlaybackSpeed(int playerId, double speed) async {}
  @override
  Future<Duration> getPosition(int playerId) async => position;
  @override
  Future<void> seekTo(int playerId, Duration value) async {
    position = value;
  }
}

void main() {
  for (final scenario in [
    'remote',
    'lifecycle',
    'close-during-initialize',
    'retry',
  ]) {
    testWidgets('TV playback: $scenario', (tester) async {
      final previous = VideoPlayerPlatform.instance;
      final player = _Player();
      player.injectable = scenario == 'retry';
      if (scenario == 'close-during-initialize') {
        player.delayInitialization = true;
      }
      addTearDown(() {
        unawaited(player.pendingEvents.close());
      });
      VideoPlayerPlatform.instance = player;
      addTearDown(() => VideoPlayerPlatform.instance = previous);
      SharedPreferences.setMockInitialValues({});
      final database = AppDatabase(NativeDatabase.memory());
      addTearDown(database.close);
      await tester.runAsync(() async {
        await database.customSelect('SELECT 1').get();
      });
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dream_cast/player_pip'),
        (call) async => call.method == 'isSupported' ? false : null,
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          const MethodChannel('dream_cast/player_pip'),
          null,
        ),
      );
      tester.binding.defaultBinaryMessenger.setMockMessageHandler(
        'dev.flutter.pigeon.wakelock_plus_platform_interface.WakelockPlusApi.toggle',
        (data) async => const StandardMessageCodec().encodeMessage([null]),
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMessageHandler(
          'dev.flutter.pigeon.wakelock_plus_platform_interface.WakelockPlusApi.toggle',
          null,
        ),
      );
      const release = DreamRelease(
        id: 1,
        title: 'TV',
        originalTitle: '',
        url: '/home/release/1',
      );
      const episode = DreamEpisode(
        id: 'ep-1',
        releaseId: 1,
        ordinal: 1,
        title: 'Episode',
        file: 'https://example.com/video.m3u8',
      );
      final stream = DreamStream(
        id: '1',
        releaseId: 1,
        episodeId: 'ep-1',
        url: Uri.parse(episode.file),
        type: DreamStreamType.hls,
        quality: 720,
      );
      final dio = Dio(BaseOptions(baseUrl: 'https://example.com'));
      addTearDown(dio.close);
      final payload =
          '#2${base64EncodeUrlComponent(jsonEncode({
            'file': [
              {'id': 'ep-1', 'title': 'Episode', 'file': 'https://example.com/fresh720.m3u8'},
            ],
          }))}';
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            handler.resolve(
              Response(
                requestOptions: options,
                statusCode: 200,
                data: options.path.contains('playerjs')
                    ? '/* direct playlist */'
                    : '<h3>TV</h3><script src="/js/playerjs.js"></script><script>new Playerjs("$payload")</script>',
              ),
            );
          },
        ),
      );
      final releases = ReleaseRepository(
        api: DreamCastApi(dio),
        cache: CacheRepository(
          database,
          retention: CacheRetentionOption.forever,
        ),
        parser: const DreamCastParserService(),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            appDatabaseProvider.overrideWithValue(database),
            releaseRepositoryProvider.overrideWithValue(releases),
          ],
          child: MaterialApp(
            home: VideoPlayerScreen(
              request: PlaybackRequest(
                release: release,
                episode: episode,
                streams: [stream],
                initialStream: stream,
              ),
            ),
          ),
        ),
      );
      for (var frame = 0; frame < 12; frame++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      if (scenario == 'close-during-initialize') {
        await tester.runAsync(() => database.latestStreamSession('1', 'ep-1'));
        await tester.pump();
        await tester.pump();
        expect(player.creationStarted.isCompleted, isTrue);
        await tester.pumpWidget(const SizedBox());
        player.pendingEvents.add(
          VideoEvent(
            eventType: VideoEventType.initialized,
            duration: const Duration(minutes: 20),
            size: const Size(1920, 1080),
          ),
        );
        await tester.pump();
        await tester.pumpAndSettle();
        await tester.runAsync(() => Future<void>.delayed(Duration.zero));
        await tester.pumpAndSettle();
        expect(player.playing, isFalse);
        expect(player.disposed, 1);
        expect(tester.takeException(), isNull);
        return;
      }
      expect(player.playing, isTrue);
      if (scenario == 'retry') {
        await tester.pump(const Duration(milliseconds: 600));
        player.pendingEvents.addError(
          PlatformException(code: 'VideoError', message: 'expired URL'),
        );
        await tester.pump();
        await tester.pump();
        expect(find.text('Повторить'), findsOneWidget);
        await tester.tap(find.text('Повторить'));
        for (var frame = 0; frame < 50 && player.sources.length < 2; frame++) {
          await tester.runAsync(() => Future<void>.delayed(Duration.zero));
          await tester.pump(const Duration(milliseconds: 50));
        }
        await tester.pump(const Duration(milliseconds: 600));
        expect(player.sources, [
          'https://example.com/video.m3u8',
          'https://example.com/fresh720.m3u8',
        ]);
        expect(player.disposed, 1);
        expect(player.position, const Duration(seconds: 30));
        expect(player.playing, isTrue);
        await tester.pumpWidget(const SizedBox());
        await tester.runAsync(() => Future<void>.delayed(Duration.zero));
        return;
      }
      if (scenario == 'lifecycle') {
        player.position = const Duration(seconds: 55);
        await tester.pump(const Duration(milliseconds: 600));
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
        await tester.pump();
        final entry = await tester.runAsync(
          () => database
              .watchEntryChanges('1', 'ep-1')
              .firstWhere((row) => row?.positionMs == 55000)
              .timeout(const Duration(seconds: 3)),
        );
        expect(entry?.positionMs, 55000);
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.resumed,
        );
        await tester.pumpWidget(const SizedBox());
        await tester.runAsync(() async {
          await database
              .watchEntryChanges('1', 'ep-1')
              .firstWhere((row) => row?.positionMs == 55000)
              .timeout(const Duration(seconds: 3));
        });
        return;
      }
      await tester.pump(const Duration(seconds: 11));
      expect(FocusManager.instance.primaryFocus?.debugLabel, 'tv-player');
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();
      expect(FocusManager.instance.primaryFocus?.debugLabel, 'tv-player-play');
      await tester.sendKeyEvent(LogicalKeyboardKey.select);
      await tester.pump();
      expect(player.playing, isFalse);
      await tester.pump(const Duration(seconds: 11));
      expect(FocusManager.instance.primaryFocus?.debugLabel, 'tv-player');
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pump();
      await tester.pump();
      expect(player.position, const Duration(seconds: 40));
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.select);
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(Dialog), findsOneWidget);
      await tester.pump(const Duration(seconds: 11));
      expect(
        FocusManager.instance.primaryFocus?.context
            ?.findAncestorWidgetOfExactType<Dialog>(),
        isNotNull,
      );
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(FocusManager.instance.primaryFocus?.debugLabel, 'tv-player-play');
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
    });
  }
}
