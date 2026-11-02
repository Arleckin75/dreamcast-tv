# Reduced LibVLC for Dream Cast TV

This directory contains source pins and the build profile, not prebuilt binaries.

`versions.env` pins the official libvlcjni checkout and the VLC base commit.
The official get-vlc.sh patch series is applied to that base. Its resulting
commit ID can differ from the base because git-am creates additional commits.

`profile.patch` disables unused disc/network-discovery/output features while
keeping the existing decoder configuration. It is applied to a clean, pinned
checkout and never directly to an AAR.

The reusable build-libvlc-tv.yml workflow builds native ARMv7 and ARM64 libraries
in VideoLAN's corresponding Linux build containers. It packages them with the
checksum-pinned original 3.7.7 Java/JNI classes and Maven dependencies. It does
not build a VLC user interface or an APK. Each ABI must retain dav1d, avcodec,
adaptive/HLS, MP4 and Android audio/video output modules. The generated
libvlc.so must be smaller than the original for each ABI.

The local Maven artifact is tv.dreamcast.libvlc:libvlc-tv:3.7.7-tv1. Application
CI downloads it before building software_AV1. There is no full-library fallback.
hardware_AV1 still uses Media3/ExoPlayer.

Artifacts contain native module/configuration reports and checksums. The custom
AAR includes assets/dreamcast-libvlc-tv.json so the APK audit can verify that
the reduced engine was packaged and that both core native libraries got smaller.

For local software builds, extract the libvlc-tv-maven Actions artifact into
.tools/libvlc-tv/maven. Do not commit the resulting AAR or generated Maven files.
The original license declarations and dependencies remain in the generated POM.
The official source repositories are:

- https://code.videolan.org/videolan/libvlcjni
- https://code.videolan.org/videolan/vlc

Profile changes require a fresh build and playback checks on both ARM ABIs.
Native module/size checks do not replace device playback tests.
