import 'package:dream_cast/app/widgets/tv_dialog.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:dream_cast/app/widgets/app_error_view.dart';
import 'package:dream_cast/features/library/data/release_bookmark_providers.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_providers.dart';
import 'package:dream_cast/features/player/data/player_providers.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/episode_list_screen.dart';
import 'package:dream_cast/features/releases/presentation/release_title_formatter.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/widgets/metadata_chip.dart';
import 'package:dream_cast/features/releases/presentation/widgets/release_poster.dart';
import 'package:dream_cast/features/releases/presentation/widgets/stale_cache_banner.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ReleaseDetailScreen extends ConsumerStatefulWidget {
  const ReleaseDetailScreen({required this.release, super.key});

  final DreamRelease release;

  @override
  ConsumerState<ReleaseDetailScreen> createState() =>
      _ReleaseDetailScreenState();
}

class _ReleaseDetailScreenState extends ConsumerState<ReleaseDetailScreen> {
  bool _descriptionExpanded = false;

  @override
  Widget build(BuildContext context) {
    final detailState = ref.watch(releaseDetailProvider(widget.release));

    return Scaffold(
      body: detailState.when(
        loading: () => _DetailSkeleton(release: widget.release),
        error: (error, stackTrace) => CustomScrollView(
          slivers: [
            _HeaderSliver(release: widget.release),
            SliverFillRemaining(
              child: AppErrorView(
                error: error,
                onRetry: () =>
                    ref.invalidate(releaseDetailProvider(widget.release)),
              ),
            ),
          ],
        ),
        data: (data) {
          final detail = data.value;
          return CustomScrollView(
            slivers: [
              _HeaderSliver(release: widget.release, detail: detail),
              if (data.isStale)
                const SliverToBoxAdapter(child: StaleCacheBanner()),
              SliverToBoxAdapter(
                child: _DetailBody(
                  release: widget.release,
                  detail: detail,
                  expanded: _descriptionExpanded,
                  onWatch: () => _showEpisodeList(context, detail),
                  onToggleDescription: () {
                    setState(
                      () => _descriptionExpanded = !_descriptionExpanded,
                    );
                  },
                ),
              ),
              const SliverToBoxAdapter(child: SizedBox(height: 104)),
            ],
          );
        },
      ),
    );
  }

  void _showEpisodeList(BuildContext context, DreamReleaseDetail detail) {
    showTvDialog<void>(
      context: context,
      builder: (context) => _EpisodeListSheet(detail: detail),
    );
  }
}

class _HeaderSliver extends StatelessWidget {
  const _HeaderSliver({required this.release, this.detail});

  final DreamRelease release;
  final DreamReleaseDetail? detail;

  @override
  Widget build(BuildContext context) {
    final imageUrl =
        detail?.thumbnailUrl ?? release.wallUrl ?? release.posterUrl;

    return SliverAppBar(
      automaticallyImplyLeading: false,
      pinned: true,
      expandedHeight: 280,
      title: Text(
        detail == null
            ? displayReleaseTitle(release)
            : displayDetailTitle(detail!),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      flexibleSpace: FlexibleSpaceBar(
        background: Stack(
          fit: StackFit.expand,
          children: [
            if (imageUrl != null)
              CachedNetworkImage(
                imageUrl: imageUrl,
                fit: BoxFit.cover,
                color: Colors.black.withValues(alpha: 0.26),
                colorBlendMode: BlendMode.darken,
              )
            else
              ColoredBox(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
              ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.transparent,
                    Theme.of(
                      context,
                    ).colorScheme.surface.withValues(alpha: 0.92),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DetailBody extends StatelessWidget {
  const _DetailBody({
    required this.release,
    required this.detail,
    required this.expanded,
    required this.onToggleDescription,
    required this.onWatch,
  });

  final DreamRelease release;
  final DreamReleaseDetail detail;
  final bool expanded;
  final VoidCallback onToggleDescription;
  final VoidCallback onWatch;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final description = detail.description ?? release.description;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 108,
                child: ReleasePoster(
                  imageUrl: detail.thumbnailUrl ?? release.posterUrl,
                  borderRadius: 12,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      displayDetailTitle(detail),
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w800,
                        height: 1.1,
                      ),
                    ),
                    if (release.originalTitle.isNotEmpty &&
                        release.originalTitle !=
                            displayDetailTitle(detail)) ...[
                      const SizedBox(height: 4),
                      Text(
                        release.originalTitle,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [
                        if (release.currentEpisodes != null)
                          MetadataChip(
                            icon: Icons.video_library_outlined,
                            label: '${release.currentEpisodes} серий',
                          ),
                        if (release.rating != null)
                          MetadataChip(
                            icon: Icons.star_outline,
                            label: release.rating!,
                          ),
                        if (release.year != null)
                          MetadataChip(
                            icon: Icons.calendar_today_outlined,
                            label: '${release.year}',
                          ),
                        if (release.type != null)
                          MetadataChip(
                            icon: Icons.movie_outlined,
                            label: release.type!,
                          ),
                      ],
                    ),
                    const SizedBox(height: 14),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            autofocus: true,
                            onPressed: onWatch,
                            icon: const Icon(Icons.play_circle_outline),
                            label: const Align(
                              alignment: Alignment.centerLeft,
                              child: Text('Смотреть'),
                            ),
                            style: FilledButton.styleFrom(
                              minimumSize: const Size(0, 52),
                              alignment: Alignment.centerLeft,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        _BookmarkStatusButton(release: release),
                        const SizedBox(width: 8),
                        _EpisodeNotificationButton(release: release),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (description?.trim().isNotEmpty == true) ...[
            const SizedBox(height: 18),
            Text(
              'Описание',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              description!,
              maxLines: expanded ? null : 8,
              overflow: expanded ? TextOverflow.visible : TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(height: 1.36),
            ),
            TextButton(
              onPressed: onToggleDescription,
              child: Text(expanded ? 'Свернуть' : 'Показать полностью'),
            ),
          ],
        ],
      ),
    );
  }
}

class _BookmarkStatusButton extends ConsumerWidget {
  const _BookmarkStatusButton({required this.release});

  final DreamRelease release;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final releaseId = release.id;
    final status = ref.watch(releaseBookmarkProvider(releaseId));
    final controller = ref.read(releaseBookmarkProvider(releaseId).notifier);

    return PopupMenuButton<Object>(
      tooltip: 'Закладки',
      position: PopupMenuPosition.under,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      onSelected: (value) {
        if (value == _BookmarkAction.remove) {
          controller.remove();
        } else if (value is ReleaseBookmarkStatus) {
          controller.setStatus(value, release: release);
        }
      },
      itemBuilder: (context) => [
        for (final value in ReleaseBookmarkStatus.values)
          PopupMenuItem<Object>(
            value: value,
            child: Row(
              children: [
                Icon(
                  status == value ? Icons.check : Icons.bookmark_border,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Text(value.label),
              ],
            ),
          ),
        const PopupMenuDivider(),
        const PopupMenuItem<Object>(
          value: _BookmarkAction.remove,
          child: Row(
            children: [
              Icon(Icons.delete_outline, size: 20),
              SizedBox(width: 12),
              Text('Удалить'),
            ],
          ),
        ),
      ],
      style: theme.iconButtonTheme.style,
      icon: Icon(status == null ? Icons.bookmark_border : Icons.bookmark),
    );
  }
}

enum _BookmarkAction { remove }

class _EpisodeNotificationButton extends ConsumerWidget {
  const _EpisodeNotificationButton({required this.release});

  final DreamRelease release;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final enabled = ref.watch(
      episodeNotificationSubscriptionProvider(release.id),
    );
    return IconButton(
      tooltip: enabled ? 'Уведомления включены' : 'Уведомлять о новых сериях',
      onPressed: () => _toggle(context, ref, enabled),
      icon: Icon(
        enabled
            ? Icons.notifications_active
            : Icons.notifications_none_outlined,
      ),
    );
  }

  Future<void> _toggle(
    BuildContext context,
    WidgetRef ref,
    bool enabled,
  ) async {
    final controller = ref.read(
      episodeNotificationSubscriptionProvider(release.id).notifier,
    );
    if (enabled) {
      await controller.disable();
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Уведомления о новых сериях выключены.')),
      );
      return;
    }

    final granted = await controller.enable(release);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          granted
              ? 'Уведомления о новых сериях включены.'
              : 'Разрешите уведомления в настройках Android.',
        ),
      ),
    );
  }
}

class _EpisodeListSheet extends ConsumerStatefulWidget {
  const _EpisodeListSheet({required this.detail});

  final DreamReleaseDetail detail;

  @override
  ConsumerState<_EpisodeListSheet> createState() => _EpisodeListSheetState();
}

class _EpisodeListSheetState extends ConsumerState<_EpisodeListSheet> {
  int _selectedQuality = 0;

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((prefs) {
      if (!mounted) return;
      final value = prefs.getInt('software_av1_quality') ?? 0;
      setState(
        () =>
            _selectedQuality = {0, 480, 720, 1080}.contains(value) ? value : 0,
      );
    });
  }

  void _changeQuality(int quality) {
    setState(() => _selectedQuality = quality);
    SharedPreferences.getInstance().then(
      (prefs) => prefs.setInt('software_av1_quality', quality),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(releaseEpisodesProvider(widget.detail));
    final theme = Theme.of(context);

    return SafeArea(
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.82,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      'Серии',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  SegmentedButton<int>(
                    showSelectedIcon: false,
                    segments: const [
                      ButtonSegment(value: 0, label: Text('Auto')),
                      ButtonSegment(value: 480, label: Text('480p')),
                      ButtonSegment(value: 720, label: Text('720p')),
                      ButtonSegment(value: 1080, label: Text('1080p')),
                    ],
                    selected: {_selectedQuality},
                    onSelectionChanged: (values) =>
                        _changeQuality(values.first),
                  ),
                ],
              ),
            ),
            Expanded(
              child: state.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (error, stackTrace) => AppErrorView(
                  error: error,
                  onRetry: () =>
                      ref.invalidate(releaseEpisodesProvider(widget.detail)),
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
                          itemBuilder: (context, index) => _EpisodeRow(
                            release: widget.detail.release,
                            episode: visible[index],
                            episodeQueue: queue,
                          ),
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EpisodeRow extends ConsumerWidget {
  const _EpisodeRow({
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
          data: (entry) => _EpisodeRowSubtitle(progress: _progressLabel(entry)),
        ),
      ),
      trailing: _EpisodeRowActions(
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

class _EpisodeRowSubtitle extends StatelessWidget {
  const _EpisodeRowSubtitle({required this.progress});

  final String progress;

  @override
  Widget build(BuildContext context) {
    return Text(progress, maxLines: 1, overflow: TextOverflow.ellipsis);
  }
}

class _EpisodeRowActions extends StatelessWidget {
  const _EpisodeRowActions({required this.onPlay});
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

class _DetailSkeleton extends StatelessWidget {
  const _DetailSkeleton({required this.release});

  final DreamRelease release;

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.surfaceContainerHighest;

    return CustomScrollView(
      slivers: [
        _HeaderSliver(release: release),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 108,
                  child: ReleasePoster(imageUrl: release.posterUrl),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _SkeletonBlock(
                        color: color,
                        height: 22,
                        widthFactor: 0.9,
                      ),
                      const SizedBox(height: 10),
                      _SkeletonBlock(
                        color: color,
                        height: 14,
                        widthFactor: 0.55,
                      ),
                      const SizedBox(height: 22),
                      _SkeletonBlock(color: color, height: 80, widthFactor: 1),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _SkeletonBlock extends StatelessWidget {
  const _SkeletonBlock({
    required this.color,
    required this.height,
    required this.widthFactor,
  });

  final Color color;
  final double height;
  final double widthFactor;

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      widthFactor: widthFactor,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(10),
        ),
        child: SizedBox(height: height),
      ),
    );
  }
}
