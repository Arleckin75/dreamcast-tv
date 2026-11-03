import 'package:dream_cast/app/widgets/tv_dialog.dart';

import 'package:dream_cast/app/widgets/app_error_view.dart';
import 'package:dream_cast/features/player/data/player_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/player/presentation/preferred_stream_launcher.dart';
import 'package:dream_cast/features/player/presentation/stream_selection_sheet.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/widgets/stale_cache_banner.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class EpisodeListScreen extends ConsumerStatefulWidget {
  const EpisodeListScreen({required this.detail, super.key});

  final DreamReleaseDetail detail;

  @override
  ConsumerState<EpisodeListScreen> createState() => _EpisodeListScreenState();
}

class _EpisodeListScreenState extends ConsumerState<EpisodeListScreen> {
  @override
  Widget build(BuildContext context) {
    final state = ref.watch(releaseEpisodesProvider(widget.detail));

    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: const Text('Серии'),
      ),
      body: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stackTrace) => AppErrorView(
          error: error,
          onRetry: () => ref.invalidate(releaseEpisodesProvider(widget.detail)),
        ),
        data: (data) {
          final queue = data.value.toList()
            ..sort((a, b) => a.ordinal.compareTo(b.ordinal));
          final visible = queue;

          return Column(
            children: [
              if (data.isStale) const StaleCacheBanner(),
              Expanded(
                child: ListView.separated(
                  padding: const EdgeInsets.only(bottom: 12),
                  itemCount: visible.length,
                  separatorBuilder: (context, index) =>
                      const Divider(height: 1, indent: 72),
                  itemBuilder: (context, index) {
                    return _EpisodeTile(
                      release: widget.detail.release,
                      episode: visible[index],
                      episodeQueue: queue,
                    );
                  },
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _EpisodeTile extends ConsumerWidget {
  const _EpisodeTile({
    required this.release,
    required this.episode,
    required this.episodeQueue,
  });

  final DreamRelease release;
  final DreamEpisode episode;
  final List<DreamEpisode> episodeQueue;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(
      episodeWatchEntryProvider((release: release, episode: episode)),
    );
    return ListTile(
      autofocus: episodeQueue.isNotEmpty && episode.id == episodeQueue.first.id,
      minVerticalPadding: 10,
      leading: _EpisodeNumberBadge(episode.ordinal),
      title: Text(episode.title, maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 3),
        child: progress.when(
          loading: () => const Text('Проверяем прогресс...'),
          error: (error, stackTrace) => const Text('Прогресс не загружен'),
          data: (entry) => _EpisodeSubtitle(progress: _progressLabel(entry)),
        ),
      ),
      trailing: _EpisodeActions(
        onPlay: () => playEpisode(
          context: context,
          ref: ref,
          release: release,
          episode: episode,
          episodeQueue: episodeQueue,
        ),
      ),
      onTap: () => playEpisode(
        context: context,
        ref: ref,
        release: release,
        episode: episode,
        episodeQueue: episodeQueue,
      ),
    );
  }

  String _progressLabel(ContinueWatchingItem? entry) {
    if (entry == null) return 'Не просмотрено';
    if (entry.isWatched) return 'Просмотрено';
    return 'Остановились на ${formatEpisodeDuration(entry.position)}';
  }
}

class _EpisodeNumberBadge extends StatelessWidget {
  const _EpisodeNumberBadge(this.number);

  final int number;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return CircleAvatar(
      radius: 20,
      backgroundColor: theme.colorScheme.secondaryContainer,
      foregroundColor: theme.colorScheme.onSecondaryContainer,
      child: Text(
        '$number',
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.onSecondaryContainer,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _EpisodeSubtitle extends StatelessWidget {
  const _EpisodeSubtitle({required this.progress});

  final String progress;

  @override
  Widget build(BuildContext context) {
    return Text(progress, maxLines: 1, overflow: TextOverflow.ellipsis);
  }
}

class _EpisodeActions extends StatelessWidget {
  const _EpisodeActions({required this.onPlay});
  final VoidCallback onPlay;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: 'Смотреть',
          onPressed: onPlay,
          icon: const Icon(Icons.play_arrow_rounded),
        ),
      ],
    );
  }
}

Future<void> playEpisode({
  required BuildContext context,
  required WidgetRef ref,
  required DreamRelease release,
  required DreamEpisode episode,
  required List<DreamEpisode> episodeQueue,
}) async {
  if (!context.mounted) return;
  final openedPreferred = await openPreferredStreamIfConfigured(
    context: context,
    ref: ref,
    release: release,
    episode: episode,
    episodeQueue: episodeQueue,
    loadStreams: () => ref.read(episodeStreamsProvider(episode).future),
  );
  if (openedPreferred || !context.mounted) return;

  showTvDialog<void>(
    context: context,
    builder: (sheetContext) => SafeArea(
      child: Consumer(
        builder: (context, sheetRef, child) {
          final streams = sheetRef.watch(episodeStreamsProvider(episode));

          return streams.when(
            loading: () => const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (error, stackTrace) => Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
              child: AppErrorView(
                error: error,
                onRetry: () {
                  Navigator.pop(sheetContext);
                  sheetRef.invalidate(episodeStreamsProvider(episode));
                },
              ),
            ),
            data: (data) {
              if (data.value.isEmpty) {
                return const Padding(
                  padding: EdgeInsets.fromLTRB(24, 8, 24, 28),
                  child: Text('Для этой серии не найдено доступных потоков.'),
                );
              }

              return StreamSelectionSheet(
                release: release,
                episode: episode,
                streams: data.value,
                episodeQueue: episodeQueue,
                isStale: data.isStale,
              );
            },
          );
        },
      ),
    ),
  );
}

String formatEpisodeDuration(Duration duration) {
  final hours = duration.inHours;
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  return hours > 0 ? '$hours:$minutes:$seconds' : '$minutes:$seconds';
}
