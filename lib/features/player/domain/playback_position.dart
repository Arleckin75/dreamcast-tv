final class PlaybackPosition {
  const PlaybackPosition({
    required this.releaseId,
    required this.episodeId,
    required this.position,
    this.duration,
    required this.updatedAt,
  });

  final String releaseId;
  final String episodeId;
  final Duration position;
  final Duration? duration;
  final DateTime updatedAt;

  /// Finished episodes restart; a deliberate seek near the end still resumes
  /// until playback reaches the last second.
  Duration get resumePosition =>
      duration != null &&
          duration! > Duration.zero &&
          position >= duration! - const Duration(seconds: 1)
      ? Duration.zero
      : position;
}
