import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/database/database_providers.dart';
import 'package:dream_cast/core/settings/player_build.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
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
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:wakelock_plus_platform_interface/wakelock_plus_platform_interface.dart';

class _ScreenLock extends WakelockPlusPlatformInterface {
  final requests = <bool>[];
  @override
  Future<void> toggle({required bool enable}) async {
    requests.add(enable);
  }

  @override
  Future<bool> get enabled async => requests.isNotEmpty && requests.last;
}

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
    if (sources.isNotEmpty) position = Duration.zero;
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
    if (softwarePlayerBuild) ...[
      'native-manual-next',
      'native-manual-retry',
    ] else ...[
      'manual-next',
      'manual-last',
      'remote',
      'lifecycle',
      'close-during-initialize',
      'retry',
      'completion-auto',
      'completion-cancel',
      'completion-last',
      'completion-error',
      'cycles',
    ],
  ]) {
    testWidgets(
      'TV playback: $scenario',
      (tester) async {
        final previous = VideoPlayerPlatform.instance;
        final player = _Player();
        player.injectable =
            scenario == 'retry' ||
            scenario.startsWith('completion') ||
            scenario == 'cycles';
        if (scenario == 'close-during-initialize') {
          player.delayInitialization = true;
        }
        addTearDown(() {
          unawaited(player.pendingEvents.close());
        });
        VideoPlayerPlatform.instance = player;
        addTearDown(() => VideoPlayerPlatform.instance = previous);
        SharedPreferences.setMockInitialValues({});
        final preferences = await SharedPreferences.getInstance();
        final nativeCalls = <MethodCall>[];
        final nativeResults = <Completer<Map<String, dynamic>>>[];
        if (softwarePlayerBuild) {
          tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
            const MethodChannel('dream_cast/media3_probe'),
            (call) {
              nativeCalls.add(call);
              final result = Completer<Map<String, dynamic>>();
              nativeResults.add(result);
              return result.future;
            },
          );
          addTearDown(
            () =>
                tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
                  const MethodChannel('dream_cast/media3_probe'),
                  null,
                ),
          );
        }
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
        final previousScreenLock = wakelockPlusPlatformInstance;
        final screenLock = _ScreenLock();
        wakelockPlusPlatformInstance = screenLock;
        final wakeRequests = screenLock.requests;
        addTearDown(() => wakelockPlusPlatformInstance = previousScreenLock);
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
        const nextEpisode = DreamEpisode(
          id: 'ep-2',
          releaseId: 1,
          ordinal: 2,
          title: 'Episode 2',
          file: 'https://example.com/next720.m3u8',
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
                {'id': 'ep-2', 'title': 'Episode 2', 'file': 'https://example.com/next720.m3u8'},
                {'id': 'ep-3', 'title': 'Episode 3', 'file': 'https://example.com/third720.m3u8'},
              ],
            }))}';
        var failNext = scenario == 'completion-error';
        dio.interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) {
              if (failNext) {
                handler.reject(
                  DioException(
                    requestOptions: options,
                    type: DioExceptionType.badResponse,
                    response: Response(
                      requestOptions: options,
                      statusCode: 400,
                    ),
                  ),
                );
                return;
              }
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
        Widget buildPlayer() => ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(preferences),
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
                episodeQueue:
                    scenario == 'completion-last' || scenario == 'manual-last'
                    ? [episode]
                    : [nextEpisode, episode],
              ),
            ),
          ),
        );
        await tester.pumpWidget(buildPlayer());
        for (var frame = 0; frame < 12; frame++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        if (softwarePlayerBuild) {
          Future<void> flush() async {
            for (var frame = 0; frame < 40; frame++) {
              await tester.runAsync(() => Future<void>.delayed(Duration.zero));
              await tester.pump(const Duration(milliseconds: 50));
            }
          }

          await flush();
          expect(nativeCalls.length, 1);
          expect(nativeCalls.first.arguments['nextTitle'], 'Episode 2');
          failNext = scenario == 'native-manual-retry';
          nativeResults.first.complete({
            'nextEpisode': true,
            'completed': false,
            'positionMs': 45000,
            'durationMs': 1200000,
            'quality': 480,
          });
          await flush();
          expect(find.text('Серия закончилась'), findsNothing);
          final progress = await tester.runAsync(
            () => database.watchEntry('1', 'ep-1'),
          );
          expect(progress?.positionMs, 45000);
          expect(progress?.isWatched, isFalse);
          if (failNext) {
            expect(nativeCalls.length, 1);
            expect(find.text('Повторить'), findsOneWidget);
            failNext = false;
            await tester.tap(find.text('Повторить'));
            await flush();
          }
          expect(nativeCalls.length, 2);
          expect(nativeCalls[1].arguments['episodeKey'], '1:ep-2');
          expect(nativeCalls[1].arguments['positionMs'], 0);
          expect(nativeCalls[1].arguments['quality'], 480);
          expect(nativeCalls[1].arguments['nextTitle'], 'Episode 3');
          // A second consecutive manual transition must outlive both async guards.
          nativeResults[1].complete({
            'nextEpisode': true,
            'completed': false,
            'positionMs': 60000,
            'durationMs': 1200000,
          });
          await flush();
          expect(nativeCalls.length, 3);
          expect(nativeCalls[2].arguments['episodeKey'], '1:ep-3');
          expect(nativeCalls[2].arguments['positionMs'], 0);
          expect(nativeCalls[2].arguments['nextTitle'], isNull);
          // Even an unexpected native "next" result cannot advance past the last episode.
          nativeResults[2].complete({
            'nextEpisode': true,
            'completed': false,
            'positionMs': 10000,
            'durationMs': 1200000,
          });
          await flush();
          expect(nativeCalls.length, 3);
          expect(find.text('Серия закончилась'), findsNothing);
          await tester.pumpWidget(const SizedBox());
          await tester.runAsync(() => Future<void>.delayed(Duration.zero));
          expect(tester.takeException(), isNull);
          return;
        }
        if (scenario == 'close-during-initialize') {
          await tester.runAsync(
            () => database.latestStreamSession('1', 'ep-1'),
          );
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
        if (scenario.startsWith('manual-')) {
          expect(find.byIcon(Icons.replay_10), findsNothing);
          expect(find.byIcon(Icons.forward_10), findsNothing);
          expect(find.byTooltip('Назад'), findsOneWidget);
          expect(find.byTooltip('Пауза'), findsOneWidget);
          if (scenario == 'manual-last') {
            expect(find.text('Следующая серия'), findsNothing);
          } else {
            await tester.tap(find.text('Следующая серия'));
            for (var frame = 0; frame < 40; frame++) {
              await tester.runAsync(() => Future<void>.delayed(Duration.zero));
              await tester.pump(const Duration(milliseconds: 50));
            }
            expect(player.sources.length, 2);
            expect(player.position, Duration.zero);
            expect(find.text('Серия закончилась'), findsNothing);
            final progress = await tester.runAsync(
              () => database.watchEntry('1', 'ep-1'),
            );
            expect(progress?.isWatched, isFalse);
            expect(progress?.positionMs, 30000);
            await tester.tap(find.text('Следующая серия'));
            for (var frame = 0; frame < 40; frame++) {
              await tester.runAsync(() => Future<void>.delayed(Duration.zero));
              await tester.pump(const Duration(milliseconds: 50));
            }
            expect(player.sources.length, 3);
            expect(find.text('Следующая серия'), findsNothing);
          }
          await tester.pumpWidget(const SizedBox());
          await tester.runAsync(() => Future<void>.delayed(Duration.zero));
          expect(tester.takeException(), isNull);
          return;
        }
        if (scenario == 'cycles') {
          for (var cycle = 0; cycle < 20; cycle++) {
            if (cycle != 0) {
              await tester.pumpWidget(buildPlayer());
              for (var frame = 0; frame < 30; frame++) {
                await tester.runAsync(
                  () => Future<void>.delayed(Duration.zero),
                );
                await tester.pump(const Duration(milliseconds: 50));
              }
            }
            await tester.pump(const Duration(minutes: 2));
            await tester.pumpWidget(const SizedBox());
            await tester.runAsync(() => Future<void>.delayed(Duration.zero));
            await tester.pump();
            expect(player.disposed, player.sources.length);
            expect(player.pendingEvents.hasListener, isFalse);
            expect(tester.takeException(), isNull);
          }
          expect(player.sources.length, 20);
          return;
        }
        if (scenario.startsWith('completion')) {
          player.pendingEvents.add(
            VideoEvent(eventType: VideoEventType.completed),
          );
          for (var frame = 0; frame < 20; frame++) {
            await tester.runAsync(() => Future<void>.delayed(Duration.zero));
            await tester.pump(const Duration(milliseconds: 50));
          }
          expect(find.text('Серия закончилась'), findsWidgets);
          expect(wakeRequests.last, isFalse);
          if (scenario == 'completion-last') {
            await tester.pump(const Duration(seconds: 20));
            expect(find.text('Следующая серия'), findsNothing);
            expect(player.sources.length, 1);
          } else {
            if (scenario == 'completion-cancel') {
              await tester.tap(find.text('Отменить переход'));
              await tester.pump(const Duration(seconds: 20));
              expect(player.sources.length, 1);
              await tester.tap(find.text('Следующая серия'));
            } else {
              await tester.pump(const Duration(seconds: 10));
            }
            for (var frame = 0; frame < 40; frame++) {
              await tester.runAsync(() => Future<void>.delayed(Duration.zero));
              await tester.pump(const Duration(milliseconds: 50));
            }
            if (scenario == 'completion-error') {
              expect(find.text('Повторить'), findsOneWidget);
              expect(player.sources.length, 1);
              failNext = false;
              await tester.tap(find.text('Повторить'));
              for (var frame = 0; frame < 40; frame++) {
                await tester.runAsync(
                  () => Future<void>.delayed(Duration.zero),
                );
                await tester.pump(const Duration(milliseconds: 50));
              }
            }
            expect(player.sources.length, 2);
            expect(player.sources.last, 'https://example.com/next720.m3u8');
            expect(player.position, Duration.zero);
            expect(player.disposed, 1);
            expect(player.playing, isTrue);
            expect(wakeRequests.last, isTrue);
          }
          final progress = await tester.runAsync(
            () => database.watchEntry('1', 'ep-1'),
          );
          expect(progress?.isWatched, isTrue);
          await tester.pumpWidget(const SizedBox());
          await tester.runAsync(() => Future<void>.delayed(Duration.zero));
          expect(tester.takeException(), isNull);
          return;
        }
        if (scenario == 'retry') {
          await tester.pump(const Duration(milliseconds: 600));
          player.pendingEvents.addError(
            PlatformException(code: 'VideoError', message: 'expired URL'),
          );
          await tester.pump();
          await tester.pump();
          expect(find.text('Повторить'), findsOneWidget);
          await tester.tap(find.text('Повторить'));
          for (
            var frame = 0;
            frame < 50 && player.sources.length < 2;
            frame++
          ) {
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
        expect(
          FocusManager.instance.primaryFocus?.debugLabel,
          'tv-player-play',
        );
        await tester.sendKeyEvent(LogicalKeyboardKey.select);
        await tester.pump();
        expect(player.playing, isFalse);
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
        await tester.pump();
        expect(player.position, const Duration(seconds: 40));
        expect(
          FocusManager.instance.primaryFocus?.debugLabel,
          'tv-player-play',
        );
        await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
        await tester.pump();
        expect(player.position, const Duration(seconds: 30));
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
        expect(
          FocusManager.instance.primaryFocus?.debugLabel,
          'tv-player-play',
        );
        await tester.pumpWidget(const SizedBox());
        await tester.pump();
      },
      variant: softwarePlayerBuild
          ? TargetPlatformVariant.only(TargetPlatform.android)
          : const DefaultTestVariant(),
    );
  }
}
