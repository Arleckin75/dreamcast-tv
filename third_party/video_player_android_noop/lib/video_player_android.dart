// Software-only build: deliberately does not provide an Android video player.
// The application's softwareAv1 flavor launches LibVLC via its native channel.
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

class VideoPlayerAndroidNoop {
  static void registerWith() {
    // Keep the default VideoPlayerPlatform unimplemented. Any accidental
    // attempt to start a Flutter VideoPlayer in softwareAv1 must fail,
    // rather than silently fall back to ExoPlayer.
  }
}
