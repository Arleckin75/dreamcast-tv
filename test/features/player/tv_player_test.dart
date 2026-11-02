import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/database/database_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/player/presentation/video_player_screen.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

class _Player extends VideoPlayerPlatform {
  bool playing = false;
  Duration position = const Duration(seconds: 30);
  @override
  Future<void> init() async {}
  @override
  Future<int?> createWithOptions(VideoCreationOptions options) async => 1;
  @override
  Stream<VideoEvent> videoEventsFor(int playerId) => Stream.value(
    VideoEvent(
      eventType: VideoEventType.initialized,
      duration: const Duration(minutes: 20),
      size: const Size(1920, 1080),
    ),
  );
  @override
  Widget buildViewWithOptions(VideoViewOptions options) => const SizedBox();
  @override
  Future<void> dispose(int playerId) async {}
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
  testWidgets(
    'hidden player controls regain focus with D-pad and OK toggles playback',
    (tester) async {
      final previous = VideoPlayerPlatform.instance;
      final player = _Player();
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
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appDatabaseProvider.overrideWithValue(database)],
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
      expect(player.playing, isTrue);
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
    },
  );
}
