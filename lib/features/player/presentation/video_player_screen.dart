import 'package:dream_cast/app/widgets/tv_dialog.dart';
import 'package:dream_cast/core/logging/performance_trace.dart';
import 'dart:async';
import 'dart:io' show File;
import 'package:dream_cast/core/settings/player_build.dart';
import 'dart:ui' show FontFeature, ImageFilter;
import 'package:flutter/foundation.dart'
    show debugPrint, kDebugMode, defaultTargetPlatform;

import 'package:dream_cast/features/player/data/player_providers.dart';
import 'package:dream_cast/features/player/data/playback_repository.dart';
import 'package:dream_cast/features/player/presentation/episode_completion_dialog.dart';
import 'package:dio/dio.dart';
import 'package:dream_cast/features/player/data/player_pip_service.dart';
import 'package:dream_cast/features/player/data/stream_preference_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/releases/data/release_providers.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_title_formatter.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

class VideoPlayerScreen extends ConsumerStatefulWidget {
  const VideoPlayerScreen({required this.request, super.key});

  final PlaybackRequest request;

  @override
  ConsumerState<VideoPlayerScreen> createState() => _VideoPlayerScreenState();
}

class _VideoPlayerScreenState extends ConsumerState<VideoPlayerScreen>
    with WidgetsBindingObserver {
  final _playerFocus = FocusNode(debugLabel: 'tv-player');
  final _playFocus = FocusNode(debugLabel: 'tv-player-play');
  static const _media3Probe = MethodChannel("dream_cast/media3_probe");
  VideoPlayerController? _controller;
  late PlaybackRepository _playbackRepository;
  Future<void> _pendingProgress = Future<void>.value();
  VideoPlayerValue? _lastPlaybackValue;
  bool _initializationInProgress = false;
  bool _retrying = false;
  bool _progressReady = false;
  DreamStream? _currentStream;
  late PlaybackRequest _request;
  Timer? _progressTimer;
  Timer? _uiTimer;
  Timer? _controlsHideTimer;
  Timer? _seekFeedbackTimer;
  bool _showControls = true;
  bool _isInitializing = true;
  bool _completionHandled = false;
  bool _nativeCompletionPending = false;
  DreamEpisode? _nativeNextPending;
  bool _isTransitioning = false;
  DreamEpisode? _pendingNextEpisode;
  CancelToken? _transitionCancel;
  (int, String, Duration, Duration, bool)? _lastQueuedProgress;
  bool _isClosing = false;
  bool _playerModeExited = false;
  bool _isInPip = false;
  String? _seekFeedbackText;
  int _seekFeedbackSeconds = 0;
  bool? _seekFeedbackBackward;
  Alignment _seekFeedbackAlignment = Alignment.centerRight;
  Object? _error;
  bool _diagLoggedInitialized = false;
  bool _diagLoggedPlaying = false;
  bool _diagLoggedError = false;
  int _diagLastSecondBucket = -1;

  void _diag(String message) {
    if (kDebugMode) debugPrint('DCTV_DIAG: $message');
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _playbackRepository = ref.read(playbackRepositoryProvider);
    _request = widget.request;
    _currentStream = _request.initialStream;
    PlayerPipService.setModeChangedHandler(_handlePipModeChanged);
    unawaited(_enterPlayerMode());
    unawaited(_initialize(stream: _request.initialStream, resume: true));
  }

  @override
  void dispose() {
    _transitionCancel?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_saveProgress());
    _playerFocus.dispose();
    _playFocus.dispose();
    _progressTimer?.cancel();
    _uiTimer?.cancel();
    _controlsHideTimer?.cancel();
    _seekFeedbackTimer?.cancel();
    PlayerPipService.setModeChangedHandler(null);
    unawaited(PlayerPipService.setEnabled(enabled: false));
    _controller?.removeListener(_onControllerChanged);
    _controller?.dispose();
    if (!_playerModeExited) unawaited(_exitPlayerMode(saveProgress: false));
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_saveProgress());
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) unawaited(_closePlayer());
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Focus(
          focusNode: _playerFocus,
          autofocus: true,
          onKeyEvent: _handleRemoteKey,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Center(
                child: _error != null
                    ? _PlayerError(error: _error!, onRetry: _retry)
                    : _completionHandled && !_isTransitioning
                    ? const Center(
                        child: Text(
                          'Серия закончилась',
                          style: TextStyle(color: Colors.white),
                        ),
                      )
                    : _isInitializing ||
                          controller == null ||
                          !controller.value.isInitialized
                    ? const _PlayerLoading()
                    : AspectRatio(
                        aspectRatio: controller.value.aspectRatio,
                        child: VideoPlayer(controller),
                      ),
              ),
              if (_seekFeedbackText != null)
                _SeekFeedback(
                  text: _seekFeedbackText!,
                  alignment: _seekFeedbackAlignment,
                ),
              if (controller != null &&
                  _error == null &&
                  !_isInitializing &&
                  !_completionHandled)
                _PlayerControls(
                  playFocus: _playFocus,
                  visible: _showControls && !_isInPip,
                  controller: controller,
                  title: _request.episode.title,
                  subtitle: displayReleaseTitle(_request.release),
                  streams: _request.streams,
                  currentStream: _currentStream,
                  onTogglePlay: () {
                    _togglePlay();
                    _scheduleControlsAutoHide();
                  },
                  onSeek: (position) {
                    unawaited(_seekTo(position));
                    _scheduleControlsAutoHide();
                  },
                  onExit: () => unawaited(_closePlayer()),
                  onNext: _nextEpisode() == null
                      ? null
                      : () => unawaited(_playNextEpisode(_nextEpisode()!)),
                  onStreamSelected: _switchStream,
                  onControlsReturned: _showControlsNow,
                ),
            ],
          ),
        ),
      ),
    );
  }

  KeyEventResult _handleRemoteKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.mediaPlay ||
        key == LogicalKeyboardKey.mediaPause) {
      final controller = _controller;
      if (controller != null && controller.value.isInitialized) {
        key == LogicalKeyboardKey.mediaPlay
            ? controller.play()
            : controller.pause();
      }
      _showControlsNow();
      return KeyEventResult.handled;
    }
    if (_showControls) _scheduleControlsAutoHide();
    if ((!_showControls || node.hasPrimaryFocus) &&
        (key == LogicalKeyboardKey.arrowUp ||
            key == LogicalKeyboardKey.arrowDown)) {
      _showControlsNow();
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.mediaPlayPause ||
        key == LogicalKeyboardKey.space ||
        (node.hasPrimaryFocus &&
            (key == LogicalKeyboardKey.select ||
                key == LogicalKeyboardKey.enter))) {
      _togglePlay();
      _showControlsNow();
      return KeyEventResult.handled;
    }
    final transportSeek =
        _playFocus.hasFocus &&
        (key == LogicalKeyboardKey.arrowLeft ||
            key == LogicalKeyboardKey.arrowRight);
    if (!node.hasPrimaryFocus &&
        _showControls &&
        !transportSeek &&
        key != LogicalKeyboardKey.mediaRewind &&
        key != LogicalKeyboardKey.mediaFastForward) {
      return KeyEventResult.ignored;
    }
    if (key == LogicalKeyboardKey.arrowLeft ||
        key == LogicalKeyboardKey.mediaRewind) {
      unawaited(_seekRelative(const Duration(seconds: -10)));
      _showSeekFeedback(true);
      _showControlsNow(requestFocus: false);
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.arrowRight ||
        key == LogicalKeyboardKey.mediaFastForward) {
      unawaited(_seekRelative(const Duration(seconds: 10)));
      _showSeekFeedback(false);
      _showControlsNow(requestFocus: false);
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
      unawaited(_closePlayer());
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  Future<void> _initialize({
    required DreamStream stream,
    required bool resume,
    Duration? preservePosition,
  }) async {
    if (!mounted || _isClosing || _initializationInProgress) return;
    _initializationInProgress = true;
    try {
      await _saveProgress();
      if (!mounted || _isClosing) return;
      await _initializePlayer(
        stream: stream,
        resume: resume,
        preservePosition: preservePosition,
      );
    } finally {
      _initializationInProgress = false;
      _drainNativeNextEpisode();
      if (_nativeCompletionPending && mounted && !_isClosing) {
        _nativeCompletionPending = false;
        unawaited(_handlePlaybackCompleted());
      }
    }
  }

  Future<void> _initializePlayer({
    required DreamStream stream,
    required bool resume,
    Duration? preservePosition,
  }) async {
    // A completed episode releases the screen lock; each subsequent episode
    // must acquire it again before playback starts.
    await WakelockPlus.enable();
    if (!mounted || _isClosing) {
      await WakelockPlus.disable();
      return;
    }
    _progressReady = false;
    setState(() {
      _isInitializing = true;
      _error = null;
      _currentStream = stream;
      _showControls = true;
      _completionHandled = false;
    });

    _diagLoggedInitialized = false;
    _diagLoggedPlaying = false;
    _diagLoggedError = false;
    _diagLastSecondBucket = -1;
    // Player backend is selected at compile time. Never switch engines at runtime.
    if (softwarePlayerBuild) {
      if (defaultTargetPlatform != TargetPlatform.android ||
          stream.url.isScheme('file')) {
        if (mounted) {
          setState(() {
            _error = StateError('LibVLC requires an Android network stream');
            _isInitializing = false;
          });
        }
        return;
      }
      try {
        final savedPosition = resume
            ? await _playbackRepository.getPosition(
                releaseId: '${_request.release.id}',
                episodeId: _request.episode.id,
              )
            : null;
        if (!mounted || _isClosing) return;
        final resumePosition =
            preservePosition ?? savedPosition?.resumePosition;
        // The native Activity returns the final watch position when it closes.
        // Write through the regular repository so the Drift-based home carousel
        // and episode progress indicators update, rather than only Android prefs.
        await ref
            .read(playbackRepositoryProvider)
            .saveDreamStreamSession(
              release: _request.release,
              episode: _request.episode,
              stream: stream,
            );
        final qualityPrefs = await SharedPreferences.getInstance();
        if (!mounted || _isClosing) return;
        final selectedQuality =
            qualityPrefs.getInt('software_av1_quality') ?? 0;
        final requestedQuality = selectedQuality == 0 ? 720 : selectedQuality;
        final nativeProgress = await _media3Probe
            .invokeMapMethod<String, dynamic>('playSoftwareAV1', {
              'url': stream.url.toString(),
              'headers': stream.headers,
              'positionMs': resumePosition?.inMilliseconds ?? 0,
              'positionUpdatedAtMs': preservePosition != null
                  ? DateTime.now().millisecondsSinceEpoch
                  : savedPosition?.updatedAt.millisecondsSinceEpoch ?? 0,
              'episodeKey': '${_request.release.id}:${_request.episode.id}',
              'title': _request.episode.title,
              'nextTitle': _nextEpisode()?.title,
              'subtitle': displayReleaseTitle(_request.release),
              'accent': Theme.of(context).colorScheme.primary.toARGB32(),
              'onAccent': Theme.of(context).colorScheme.onPrimary.toARGB32(),
              'quality': requestedQuality,
            });
        if (!mounted || _isClosing) return;
        if (nativeProgress != null) {
          final nativeQuality = (nativeProgress['quality'] as num?)?.toInt();
          if (const [480, 720, 1080].contains(nativeQuality)) {
            await qualityPrefs.setInt('software_av1_quality', nativeQuality!);
            if (!mounted || _isClosing) return;
          }
          final positionMs =
              (nativeProgress['positionMs'] as num?)?.toInt() ?? 0;
          final durationMs =
              (nativeProgress['durationMs'] as num?)?.toInt() ?? 0;
          final completed = nativeProgress['completed'] == true;
          if (durationMs > 0 || positionMs > 0 || completed) {
            await ref
                .read(playbackRepositoryProvider)
                .saveWatchProgress(
                  release: _request.release,
                  episode: _request.episode,
                  position: Duration(
                    milliseconds: completed && durationMs > 0
                        ? durationMs
                        : positionMs,
                  ),
                  duration: durationMs > 0
                      ? Duration(milliseconds: durationMs)
                      : null,
                  completed: completed,
                );
            _diag(
              'native progress synced to home: positionMs=$positionMs durationMs=$durationMs completed=$completed',
            );
          }
          final nextEpisode = _nextEpisode();
          if (nativeProgress['nextEpisode'] == true && nextEpisode != null) {
            _nativeNextPending = nextEpisode;
            return;
          }
          if (nativeProgress['playbackError'] == true) {
            setState(() {
              _error = StateError('Ошибка воспроизведения LibVLC');
              _isInitializing = false;
            });
            return;
          }
          if (completed) {
            _completionHandled = true;
            _nativeCompletionPending = true;
            return;
          }
          if (mounted) await _closePlayer();
          return;
        }
        if (mounted) {
          setState(() {
            _error = StateError('Не удалось запустить LibVLC');
            _isInitializing = false;
          });
        }
      } catch (error) {
        if (mounted) {
          setState(() {
            _error = error;
            _isInitializing = false;
          });
        }
      }
      return;
    }
    // hardwareAv1: ExoPlayer only, never attempt LibVLC fallback.
    if (!mounted || _isClosing) return;
    _currentStream = stream;
    _diag(
      'open stream scheme=${stream.url.scheme} host=${stream.url.host} '
      'headers=${stream.headers.keys.join(",")} (URL/path omitted)',
    );
    _uiTimer?.cancel();
    _controlsHideTimer?.cancel();
    _progressTimer?.cancel();
    final old = _controller;
    _controller = null;
    _lastPlaybackValue = null;
    old?.removeListener(_onControllerChanged);
    await old?.dispose();
    if (!mounted || _isClosing) return;

    try {
      final VideoPlayerController controller;
      if (stream.url.isScheme('file')) {
        controller = VideoPlayerController.file(
          File(stream.url.toFilePath()),
          videoPlayerOptions: VideoPlayerOptions(mixWithOthers: false),
        );
      } else {
        controller = VideoPlayerController.networkUrl(
          stream.url,
          httpHeaders: stream.headers,
          videoPlayerOptions: VideoPlayerOptions(mixWithOthers: false),
        );
      }
      _controller = controller;
      controller.addListener(_onControllerChanged);
      await _playbackRepository.saveDreamStreamSession(
        release: _request.release,
        episode: _request.episode,
        stream: stream,
      );
      if (!_ownsController(controller)) return;
      await PerformanceTrace.measure(
        'player.hardware.initialize',
        controller.initialize,
      );
      if (!_ownsController(controller)) return;
      _diag(
        'initialize completed size=${controller.value.size} '
        'aspect=${controller.value.aspectRatio} '
        'duration=${controller.value.duration} '
        'hasError=${controller.value.hasError}',
      );
      await PlayerPipService.setEnabled(
        enabled: true,
        aspectRatio: controller.value.aspectRatio,
      );
      if (!_ownsController(controller)) return;

      final resumePosition =
          preservePosition ??
          (resume
              ? (await ref
                        .read(playbackRepositoryProvider)
                        .getPosition(
                          releaseId: '${_request.release.id}',
                          episodeId: _request.episode.id,
                        ))
                    ?.resumePosition
              : null);
      if (!_ownsController(controller)) return;
      if (resumePosition != null &&
          resumePosition > const Duration(seconds: 5)) {
        await controller.seekTo(
          _clampDuration(resumePosition, controller.value.duration),
        );
      }
      if (!_ownsController(controller)) return;
      _progressReady = true;
      await controller.play();
      if (!_ownsController(controller)) return;
      _diag('play requested successfully; playback state will be sampled');
      _startProgressTimer();
      _startUiTimer();
      _scheduleControlsAutoHide();

      if (mounted && !_isClosing) {
        setState(() => _isInitializing = false);
      }
    } catch (error) {
      _diag('initialize/play exception: $error');
      if (mounted) {
        setState(() {
          _error = error;
          _isInitializing = false;
        });
      }
    }
  }

  bool _ownsController(VideoPlayerController controller) =>
      mounted && !_isClosing && identical(_controller, controller);

  Future<void> _switchStream(DreamStream stream) async {
    final position = _controller?.value.position;
    Navigator.pop(context);
    await _saveProgress();
    await _initialize(
      stream: stream,
      resume: false,
      preservePosition: position,
    );
  }

  void _handlePipModeChanged(bool active) {
    if (!mounted) return;
    setState(() {
      _isInPip = active;
      if (active) _showControls = false;
    });
  }

  void _onControllerChanged() {
    final controller = _controller;
    if (controller == null || !mounted) return;
    if (controller.value.hasError) {
      if (!_diagLoggedError) {
        unawaited(_saveProgress());
        _progressTimer?.cancel();
        _uiTimer?.cancel();
        _controlsHideTimer?.cancel();
        _diagLoggedError = true;
        _diag('controller ERROR: ${controller.value.errorDescription}');
      }
      setState(
        () => _error =
            controller.value.errorDescription ?? 'Ошибка воспроизведения',
      );
      return;
    }

    final value = controller.value;
    final wasPlaying = _lastPlaybackValue?.isPlaying ?? false;
    if (value.isInitialized) _lastPlaybackValue = value;
    if (wasPlaying && !value.isPlaying) unawaited(_saveProgress());
    if (value.isInitialized && !_diagLoggedInitialized) {
      _diagLoggedInitialized = true;
      _diag(
        'controller initialized: size=${value.size} duration=${value.duration}',
      );
    }
    if (value.isPlaying && !_diagLoggedPlaying) {
      _diagLoggedPlaying = true;
      _diag('controller entered isPlaying=true');
    }
    if (value.isInitialized) {
      final bucket = value.position.inSeconds ~/ 5;
      if (bucket != _diagLastSecondBucket) {
        _diagLastSecondBucket = bucket;
        _diag(
          'sample position=${value.position} size=${value.size} '
          'isPlaying=${value.isPlaying} isBuffering=${value.isBuffering} '
          'hasError=${value.hasError}',
        );
      }
    }
    final duration = controller.value.duration;
    final position = controller.value.position;
    if (!_completionHandled &&
        _progressReady &&
        (value.isCompleted ||
            (duration > Duration.zero &&
                position >= duration &&
                !value.isPlaying))) {
      _completionHandled = true;
      unawaited(_handlePlaybackCompleted());
    }
  }

  void _togglePlay() {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) return;
    controller.value.isPlaying ? controller.pause() : controller.play();
    setState(() {});
  }

  Future<void> _seekTo(Duration position) async {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) return;
    await controller.seekTo(
      _clampDuration(position, controller.value.duration),
    );
    if (mounted) setState(() {});
  }

  Future<void> _seekRelative(Duration offset) async {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) return;
    final target = controller.value.position + offset;
    await controller.seekTo(_clampDuration(target, controller.value.duration));
    if (mounted) setState(() {});
  }

  void _showSeekFeedback(bool backward) {
    _seekFeedbackTimer?.cancel();
    setState(() {
      if (_seekFeedbackBackward == backward && _seekFeedbackText != null) {
        _seekFeedbackSeconds += 10;
      } else {
        _seekFeedbackBackward = backward;
        _seekFeedbackSeconds = 10;
      }
      _seekFeedbackText = backward
          ? '-$_seekFeedbackSeconds сек'
          : '+$_seekFeedbackSeconds сек';
      _seekFeedbackAlignment = backward
          ? Alignment.centerLeft
          : Alignment.centerRight;
    });
    _seekFeedbackTimer = Timer(const Duration(milliseconds: 900), () {
      if (!mounted) return;
      setState(() {
        _seekFeedbackText = null;
        _seekFeedbackSeconds = 0;
        _seekFeedbackBackward = null;
      });
    });
  }

  Future<void> _closePlayer() async {
    if (_isClosing) return;
    _isClosing = true;
    _transitionCancel?.cancel();
    await _exitPlayerMode();
    if (mounted) Navigator.pop(context);
  }

  Future<void> _retry() async {
    if (_retrying || _initializationInProgress || _isClosing) return;
    final pending = _pendingNextEpisode;
    if (pending != null) {
      await _playNextEpisode(pending);
      return;
    }
    var stream = _currentStream;
    if (stream == null) return;
    _retrying = true;
    setState(() {
      _isInitializing = true;
      _error = null;
    });
    try {
      await _saveProgress();
      if (!stream.url.isScheme('file')) {
        final repository = ref.read(releaseRepositoryProvider);
        final detail = await repository.getDetailById(
          _request.release.id,
          seed: _request.release,
        );
        final episodes = await repository.getEpisodes(detail.value);
        final episode = episodes.value.firstWhere(
          (item) => item.id == _request.episode.id,
          orElse: () => throw StateError('Серия больше не доступна'),
        );
        final streams = await repository.getStreams(episode, refresh: true);
        final previous = stream;
        stream = streams.value.firstWhere(
          (item) =>
              item.type == previous.type && item.quality == previous.quality,
          orElse: () => _pickPreferredStream(streams.value),
        );
        if (!mounted || _isClosing) return;
        _request = PlaybackRequest(
          release: detail.value.release,
          episode: episode,
          streams: streams.value,
          initialStream: stream,
          episodeQueue: episodes.value,
        );
      }
      if (mounted && !_isClosing) {
        await _initialize(stream: stream, resume: true);
      }
    } catch (error) {
      if (mounted && !_isClosing) {
        setState(() {
          _error = error;
          _isInitializing = false;
        });
      }
    } finally {
      _retrying = false;
    }
  }

  void _startProgressTimer() {
    _progressTimer?.cancel();
    _progressTimer = Timer.periodic(const Duration(seconds: 8), (_) {
      unawaited(_saveProgress());
    });
  }

  void _startUiTimer() {
    _uiTimer?.cancel();
    _uiTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      final controller = _controller;
      if (!mounted ||
          !_showControls ||
          _isInPip ||
          _completionHandled ||
          !(ModalRoute.of(context)?.isCurrent ?? false) ||
          controller == null ||
          !controller.value.isInitialized) {
        return;
      }
      setState(() {});
    });
  }

  void _showControlsNow({bool requestFocus = true}) {
    if (!mounted) return;
    setState(() => _showControls = true);
    if (requestFocus) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            _showControls &&
            !_isInPip &&
            _playFocus.canRequestFocus) {
          _playFocus.requestFocus();
        }
      });
    }
    _scheduleControlsAutoHide();
  }

  void _scheduleControlsAutoHide() {
    _controlsHideTimer?.cancel();
    _controlsHideTimer = Timer(const Duration(seconds: 10), () {
      if (!mounted || _isClosing) return;
      setState(() => _showControls = false);
      if (!_isInPip && (ModalRoute.of(context)?.isCurrent ?? false)) {
        _playerFocus.requestFocus();
      }
    });
  }

  Future<void> _handlePlaybackCompleted() async {
    _progressTimer?.cancel();
    _uiTimer?.cancel();
    _controlsHideTimer?.cancel();
    await _saveProgress(completed: true);
    await WakelockPlus.disable();
    if (!mounted || _isClosing) return;
    setState(() => _isInitializing = false);
    final nextEpisode = _nextEpisode();
    final action = await showDialog<EpisodeCompletionAction>(
      context: context,
      barrierDismissible: false,
      builder: (context) =>
          EpisodeCompletionDialog(nextTitle: nextEpisode?.title),
    );
    if (!mounted || _isClosing) return;
    if (action == EpisodeCompletionAction.next && nextEpisode != null) {
      await _playNextEpisode(nextEpisode);
    } else {
      await _closePlayer();
    }
  }

  void _drainNativeNextEpisode() {
    final next = _nativeNextPending;
    if (next == null ||
        !mounted ||
        _isClosing ||
        _initializationInProgress ||
        _isTransitioning) {
      return;
    }
    _nativeNextPending = null;
    unawaited(_playNextEpisode(next));
  }

  DreamEpisode? _nextEpisode() {
    final queue =
        _request.episodeQueue
            .where(
              (episode) =>
                  episode.releaseId == _request.release.id &&
                  episode.ordinal > _request.episode.ordinal,
            )
            .toList()
          ..sort((a, b) => a.ordinal.compareTo(b.ordinal));
    return queue.isEmpty ? null : queue.first;
  }

  Future<void> _playNextEpisode(DreamEpisode episode) async {
    if (!mounted ||
        _isClosing ||
        _initializationInProgress ||
        _isTransitioning) {
      return;
    }
    _isTransitioning = true;
    _pendingNextEpisode = episode;
    final token = _transitionCancel = CancelToken();
    final repository = ref.read(releaseRepositoryProvider);
    setState(() {
      _isInitializing = true;
      _showControls = true;
      _error = null;
    });

    try {
      _progressTimer?.cancel();
      _uiTimer?.cancel();
      await _saveProgress();
      final previous = _controller;
      _controller = null;
      _lastPlaybackValue = null;
      _progressReady = false;
      previous?.removeListener(_onControllerChanged);
      await previous?.dispose();
      if (!mounted || _isClosing) return;
      final detail = await repository.getDetailById(
        _request.release.id,
        seed: _request.release,
        cancelToken: token,
      );
      final episodes = await repository.getEpisodes(
        detail.value,
        cancelToken: token,
      );
      if (!mounted || _isClosing || token.isCancelled) return;
      final freshEpisode = episodes.value.firstWhere(
        (item) => item.id == episode.id || item.ordinal == episode.ordinal,
        orElse: () => throw StateError('Следующая серия больше не доступна.'),
      );
      final data = await repository.getStreams(freshEpisode, refresh: true);
      if (!mounted || _isClosing || token.isCancelled) return;
      if (data.value.isEmpty) {
        throw StateError('Для следующей серии не найден поток.');
      }
      final preferred = _pickPreferredStream(data.value);
      _request = PlaybackRequest(
        release: detail.value.release,
        episode: freshEpisode,
        streams: data.value,
        initialStream: preferred,
        episodeQueue: episodes.value,
      );
      _pendingNextEpisode = null;
      await _initialize(
        stream: preferred,
        resume: false,
        preservePosition: Duration.zero,
      );
    } catch (error) {
      if (!mounted || _isClosing || token.isCancelled) return;
      setState(() {
        _error = error;
        _isInitializing = false;
      });
    } finally {
      _isTransitioning = false;
      _drainNativeNextEpisode();
    }
  }

  DreamStream _pickPreferredStream(List<DreamStream> streams) {
    final preference = ref.read(preferredStreamTechnologyProvider);
    final current = _currentStream;
    return pickPreferredStream(streams, preference, fallback: current);
  }

  Future<void> _saveProgress({bool completed = false}) async {
    if (!_progressReady) return _pendingProgress;
    final controller = _controller;
    final value = controller != null && controller.value.isInitialized
        ? controller.value
        : _lastPlaybackValue;
    if (value == null || !value.isInitialized) return;
    final request = _request;
    completed = completed || _completionHandled;
    final position = completed && value.duration > Duration.zero
        ? value.duration
        : value.position;
    final snapshot = (
      request.release.id,
      request.episode.id,
      position,
      value.duration,
      completed,
    );
    if (_lastQueuedProgress == snapshot) return _pendingProgress;
    _lastQueuedProgress = snapshot;
    _pendingProgress = _pendingProgress.then((_) async {
      try {
        await _playbackRepository.saveWatchProgress(
          release: request.release,
          episode: request.episode,
          position: position,
          duration: value.duration,
          completed: completed,
        );
      } catch (error) {
        if (_lastQueuedProgress == snapshot) _lastQueuedProgress = null;
        _diag('Could not save playback progress: ${error.runtimeType}');
      }
    });
    await _pendingProgress;
  }

  Future<void> _enterPlayerMode() async {
    _playerModeExited = false;
    await WakelockPlus.enable();
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
  }

  Future<void> _exitPlayerMode({bool saveProgress = true}) async {
    if (_playerModeExited) return;
    _playerModeExited = true;
    if (saveProgress) await _saveProgress();
    await PlayerPipService.setEnabled(enabled: false);
    await WakelockPlus.disable();
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  Duration _clampDuration(Duration value, Duration max) {
    if (value < Duration.zero) return Duration.zero;
    if (max > Duration.zero && value > max) return max;
    return value;
  }
}

class _PlayerControls extends StatelessWidget {
  const _PlayerControls({
    required this.visible,
    required this.controller,
    required this.title,
    required this.subtitle,
    required this.streams,
    required this.currentStream,
    required this.playFocus,
    required this.onTogglePlay,
    required this.onSeek,
    required this.onExit,
    required this.onNext,
    required this.onStreamSelected,
    required this.onControlsReturned,
  });

  final bool visible;
  final VideoPlayerController controller;
  final String title;
  final String subtitle;
  final List<DreamStream> streams;
  final DreamStream? currentStream;
  final FocusNode playFocus;
  final VoidCallback onTogglePlay;
  final ValueChanged<Duration> onSeek;
  final VoidCallback onExit;
  final VoidCallback? onNext;
  final ValueChanged<DreamStream> onStreamSelected;
  final VoidCallback onControlsReturned;

  @override
  Widget build(BuildContext context) {
    final value = controller.value;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final size = MediaQuery.sizeOf(context);
    final compact = size.width < 640 || size.height < 420;
    final horizontalPadding = compact ? 12.0 : 28.0;

    return ExcludeFocus(
      excluding: !visible,
      child: ExcludeSemantics(
        excluding: !visible,
        child: IgnorePointer(
          ignoring: !visible,
          child: AnimatedOpacity(
            opacity: visible ? 1 : 0,
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.58),
                    Colors.black.withValues(alpha: 0.08),
                    Colors.black.withValues(alpha: 0.64),
                  ],
                  stops: const [0, 0.48, 1],
                ),
              ),
              child: SafeArea(
                minimum: EdgeInsets.fromLTRB(
                  horizontalPadding,
                  compact ? 8 : 12,
                  horizontalPadding,
                  compact ? 8 : 14,
                ),
                child: Column(
                  children: [
                    _TopBar(
                      title: title,
                      subtitle: subtitle,
                      compact: compact,
                      currentStream: currentStream,
                      onQualityTap: () => _showQualitySheet(context),
                      onExit: onExit,
                      onNext: onNext,
                    ),
                    const Spacer(),
                    AnimatedScale(
                      scale: visible ? 1 : 0.96,
                      duration: const Duration(milliseconds: 220),
                      curve: Curves.easeOutCubic,
                      child: _CenterTransportControls(
                        playFocus: playFocus,
                        isPlaying: value.isPlaying,
                        compact: compact,
                        onTogglePlay: onTogglePlay,
                      ),
                    ),
                    const Spacer(),
                    _BottomControls(
                      position: value.position,
                      duration: value.duration,
                      compact: compact,
                      accentColor: colors.primary,
                      onSeek: onSeek,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _showQualitySheet(BuildContext context) async {
    await showTvDialog<void>(
      context: context,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.only(bottom: 16),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: Text(
                'Качество потока',
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
              ),
            ),
            for (final stream in streams)
              ListTile(
                selected: stream == currentStream,
                autofocus: stream == currentStream,
                onTap: () => onStreamSelected(stream),
                leading: Icon(
                  stream == currentStream
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked,
                ),
                title: Text(
                  '${_streamLabel(stream.type)} • ${stream.quality}p',
                ),
                subtitle: Text(stream.url.host),
              ),
          ],
        ),
      ),
    );
    onControlsReturned();
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.title,
    required this.subtitle,
    required this.compact,
    required this.currentStream,
    required this.onQualityTap,
    required this.onExit,
    required this.onNext,
  });

  final String title;
  final String subtitle;
  final bool compact;
  final DreamStream? currentStream;
  final VoidCallback onQualityTap;
  final VoidCallback onExit;
  final VoidCallback? onNext;

  @override
  Widget build(BuildContext context) {
    final stream = currentStream;

    return Row(
      children: [
        _FloatingIconButton(
          tooltip: 'Назад',
          icon: Icons.arrow_back,
          onPressed: onExit,
          size: compact ? 48 : 56,
        ),
        const SizedBox(width: 12),
        Expanded(
          child: _FloatingPill(
            minHeight: compact ? 48 : 56,
            padding: EdgeInsets.symmetric(
              horizontal: compact ? 14 : 18,
              vertical: compact ? 7 : 9,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: compact ? 15 : 17,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (!compact) ...[
                  const SizedBox(height: 1),
                  Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white70, fontSize: 13),
                  ),
                ],
              ],
            ),
          ),
        ),
        SizedBox(width: compact ? 8 : 12),
        _FloatingTextButton(
          tooltip: 'Качество',
          label: stream == null ? 'Поток' : '${stream.quality}p',
          icon: Icons.high_quality_outlined,
          compact: compact,
          onPressed: onQualityTap,
        ),
        if (onNext != null) ...[
          const SizedBox(width: 12),
          TextButton(
            onPressed: onNext,
            child: Text(
              'Следующая серия',
              style: TextStyle(fontSize: compact ? 12 : 14),
            ),
          ),
        ],
      ],
    );
  }
}

class _CenterTransportControls extends StatelessWidget {
  const _CenterTransportControls({
    required this.isPlaying,
    required this.compact,
    required this.onTogglePlay,
    required this.playFocus,
  });

  final bool isPlaying;
  final bool compact;
  final VoidCallback onTogglePlay;
  final FocusNode playFocus;

  @override
  Widget build(BuildContext context) {
    final playSize = compact ? 72.0 : 88.0;

    return _FloatingIconButton(
      tooltip: isPlaying ? 'Пауза' : 'Воспроизвести',
      focusNode: playFocus,
      icon: isPlaying ? Icons.pause : Icons.play_arrow,
      onPressed: onTogglePlay,
      size: playSize,
      iconSize: compact ? 42 : 50,
    );
  }
}

class _BottomControls extends StatelessWidget {
  const _BottomControls({
    required this.position,
    required this.duration,
    required this.compact,
    required this.accentColor,
    required this.onSeek,
  });

  final Duration position;
  final Duration duration;
  final bool compact;
  final Color accentColor;
  final ValueChanged<Duration> onSeek;

  @override
  Widget build(BuildContext context) {
    final effectiveDuration = duration <= Duration.zero
        ? const Duration(seconds: 1)
        : duration;
    final max = effectiveDuration.inMilliseconds.toDouble();
    final value = position.inMilliseconds.clamp(0, max.toInt()).toDouble();

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          children: [
            _TimeLabel(_formatDuration(position)),
            Expanded(
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: compact ? 5 : 7,
                  thumbShape: RoundSliderThumbShape(
                    enabledThumbRadius: compact ? 6 : 7,
                  ),
                  overlayShape: RoundSliderOverlayShape(
                    overlayRadius: compact ? 18 : 22,
                  ),
                  activeTrackColor: accentColor,
                  inactiveTrackColor: Colors.white.withValues(alpha: 0.26),
                  thumbColor: accentColor,
                  overlayColor: accentColor.withValues(alpha: 0.18),
                ),
                child: Slider(
                  min: 0,
                  max: max,
                  value: value,
                  onChanged: (next) {
                    onSeek(Duration(milliseconds: next.round()));
                  },
                ),
              ),
            ),
            _TimeLabel(_formatDuration(duration)),
          ],
        ),
      ],
    );
  }
}

class _FloatingPill extends StatelessWidget {
  const _FloatingPill({
    required this.child,
    required this.minHeight,
    required this.padding,
  });

  final Widget child;
  final double minHeight;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(999),
      child: _BackdropFilterLayer(
        child: Container(
          constraints: BoxConstraints(minHeight: minHeight),
          padding: padding,
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.42),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: Colors.white.withValues(alpha: 0.13)),
          ),
          child: child,
        ),
      ),
    );
  }
}

class _FloatingIconButton extends StatelessWidget {
  const _FloatingIconButton({
    required this.tooltip,
    required this.icon,
    required this.onPressed,
    required this.size,
    this.iconSize = 28,
    this.focusNode,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback onPressed;
  final double size;
  final double iconSize;
  final FocusNode? focusNode;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: size,
      child: IconButton(
        tooltip: tooltip,
        focusNode: focusNode,
        onPressed: onPressed,
        icon: Icon(icon, size: iconSize),
        style: IconButton.styleFrom(minimumSize: Size.square(size)),
      ),
    );
  }
}

class _FloatingTextButton extends StatelessWidget {
  const _FloatingTextButton({
    required this.tooltip,
    required this.label,
    required this.icon,
    required this.compact,
    required this.onPressed,
  });

  final String tooltip;
  final String label;
  final IconData icon;
  final bool compact;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: TextButton.icon(
        onPressed: onPressed,
        icon: Icon(icon, size: compact ? 20 : 22),
        label: Text(label),
        style: TextButton.styleFrom(
          padding: EdgeInsets.symmetric(
            horizontal: compact ? 12 : 16,
            vertical: compact ? 12 : 16,
          ),
        ),
      ),
    );
  }
}

class _BackdropFilterLayer extends StatelessWidget {
  const _BackdropFilterLayer({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    // A tiny blur gives dark controls a soft Material surface feeling without
    // turning the whole player into a glassmorphism layout.
    return BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
      child: child,
    );
  }
}

class _SeekFeedback extends StatelessWidget {
  const _SeekFeedback({required this.text, required this.alignment});

  final String text;
  final Alignment alignment;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;

    return Align(
      alignment: alignment,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 42),
        child: AnimatedScale(
          scale: 1,
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: colors.primary.withValues(alpha: 0.88),
              borderRadius: BorderRadius.circular(999),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.28),
                  blurRadius: 18,
                  offset: const Offset(0, 8),
                ),
              ],
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 11),
              child: Text(
                text,
                style: TextStyle(
                  color: colors.onPrimary,
                  fontWeight: FontWeight.w900,
                  fontSize: 16,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _TimeLabel extends StatelessWidget {
  const _TimeLabel(this.value);

  final String value;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 64,
      child: Text(
        value,
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w800,
          fontFeatures: [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}

class _PlayerLoading extends StatelessWidget {
  const _PlayerLoading();

  @override
  Widget build(BuildContext context) {
    return CircularProgressIndicator(
      color: Theme.of(context).colorScheme.primary,
    );
  }
}

class _PlayerError extends StatelessWidget {
  const _PlayerError({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.error_outline, color: colors.primary, size: 44),
          const SizedBox(height: 16),
          const Text(
            'Не удалось воспроизвести серию',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            'Возможно у вас нет доступа к интернету или поток временно недоступен.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white70),
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            autofocus: true,
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: const Text('Повторить'),
          ),
        ],
      ),
    );
  }
}

String _streamLabel(DreamStreamType type) {
  return switch (type) {
    DreamStreamType.hls => 'HLS',
    DreamStreamType.dash => 'DASH',
    DreamStreamType.mp4 => 'MP4',
    DreamStreamType.webm => 'WebM',
    DreamStreamType.audio => 'Аудио',
    DreamStreamType.unknown => 'Поток',
  };
}

String _formatDuration(Duration duration) {
  final normalized = duration < Duration.zero ? Duration.zero : duration;
  final hours = normalized.inHours;
  final minutes = normalized.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = normalized.inSeconds.remainder(60).toString().padLeft(2, '0');
  return hours > 0 ? '$hours:$minutes:$seconds' : '$minutes:$seconds';
}
