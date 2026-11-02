import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_title_formatter.dart';
import 'package:dream_cast/features/releases/presentation/widgets/release_poster.dart';
import 'package:flutter/material.dart';

class ReleaseCard extends StatefulWidget {
  const ReleaseCard({
    required this.release,
    required this.onTap,
    this.focusNode,
    super.key,
  });

  final DreamRelease release;
  final VoidCallback onTap;
  final FocusNode? focusNode;

  @override
  State<ReleaseCard> createState() => _ReleaseCardState();
}

class _ReleaseCardState extends State<ReleaseCard> {
  bool _focused = false;
  DreamRelease get release => widget.release;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return InkWell(
      focusNode: widget.focusNode,
      onTap: widget.onTap,
      onFocusChange: (focused) {
        if (mounted) setState(() => _focused = focused);
      },
      autofocus: false,
      focusColor: theme.colorScheme.primary.withValues(alpha: 0.24),
      hoverColor: theme.colorScheme.primary.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(10),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: _focused ? theme.colorScheme.primary : Colors.transparent,
            width: 3,
          ),
        ),
        padding: const EdgeInsets.all(4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: ReleasePoster(imageUrl: release.posterUrl)),
            const SizedBox(height: 6),
            Text(
              displayReleaseTitle(release),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w700,
                color: theme.colorScheme.onSurface,
                height: 1.15,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              _subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w500,
                fontSize: 14,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String get _subtitle {
    final parts = [
      if (release.currentEpisodes != null) '${release.currentEpisodes} сер.',
      if (release.type != null && release.type!.trim().isNotEmpty)
        release.type!,
      if (release.year != null) '${release.year}',
    ];
    return parts.isEmpty ? 'Dream Cast' : parts.join(' • ');
  }
}

class ReleaseListTile extends StatelessWidget {
  const ReleaseListTile({
    required this.release,
    required this.onTap,
    super.key,
  });

  final DreamRelease release;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListTile(
      onTap: onTap,
      leading: SizedBox(
        width: 48,
        child: ReleasePoster(imageUrl: release.posterUrl, borderRadius: 8),
      ),
      title: Text(
        displayReleaseTitle(release),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        [
          if (release.currentEpisodes != null)
            '${release.currentEpisodes} серий',
          if (release.status != null) release.status!,
          if (release.rating != null) '★ ${release.rating}',
        ].join(' • '),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Icon(
        Icons.chevron_right,
        color: theme.colorScheme.onSurfaceVariant,
      ),
    );
  }
}
