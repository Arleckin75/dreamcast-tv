import 'package:dream_cast/features/releases/domain/release.dart';

String releaseLocation(int id) => '/release/$id';

String releaseEpisodesLocation(int id) => '/release/$id/episodes';

String watchLocation(int releaseId, String episodeId) {
  return '/watch/$releaseId/${Uri.encodeComponent(episodeId)}';
}

DreamRelease? releaseExtra(Object? extra, int id) {
  return extra is DreamRelease && extra.id == id ? extra : null;
}
