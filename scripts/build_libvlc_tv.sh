#!/usr/bin/env bash
# Runs in the official VideoLAN Linux build container. Builds libraries, not an APK.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$project_root/third_party/libvlc_tv/versions.env"
abi="${1:?Pass armeabi-v7a or arm64-v8a}"
case "$abi" in
  armeabi-v7a) triplet=arm-linux-androideabi ;;
  arm64-v8a) triplet=aarch64-linux-android ;;
  *) echo 'Unsupported ABI' >&2; exit 1 ;;
esac
: "${ANDROID_NDK:?Use the official VideoLAN Android build container}"
work="$project_root/.tools/libvlc-tv-build/$abi"
[[ ! -e "$work" ]] || { echo 'Refusing to reuse an existing source checkout' >&2; exit 1; }
mkdir -p "$work"
git init "$work"
git -C "$work" fetch --depth 1 https://code.videolan.org/videolan/libvlcjni.git "$LIBVLCJNI_REVISION"
git -C "$work" checkout --detach FETCH_HEAD
test "$(git -C "$work" rev-parse HEAD)" = "$LIBVLCJNI_REVISION"
git -C "$work" apply --check "$project_root/third_party/libvlc_tv/profile.patch"
git -C "$work" apply "$project_root/third_party/libvlc_tv/profile.patch"
git init "$work/vlc"
git -C "$work/vlc" fetch --depth 1 https://code.videolan.org/videolan/vlc.git "$VLC_REVISION"
git -C "$work/vlc" checkout --detach FETCH_HEAD
git -C "$work/vlc" config user.name 'Dream Cast TV build'
git -C "$work/vlc" config user.email 'build@dreamcast.invalid'
cd "$work"
# Upstream's published VLC revision includes these patches; keep the same base
# and patch series instead of treating the post-git-am commit hash as a base.
./buildsystem/get-vlc.sh --reset --vlchash "$VLC_REVISION"
export MAKEFLAGS="-j$(nproc)"
./buildsystem/compile-libvlc.sh -a "$abi" --release

output="$project_root/dist/libvlc-tv/native/$abi"
mkdir -p "$output"
modules="$work/vlc/build-android-$triplet/ndk/libvlcjni-modules.c"
test -s "$modules"
for module in dav1d avcodec adaptive mp4; do
  grep -q "vlc_entry__${module}[,(]" "$modules" || {
    echo "Required playback module missing: $module" >&2; exit 1;
  }
done
grep -Eq 'vlc_entry__(audiotrack|android_audiotrack|opensles_android)[,(]' "$modules"
grep -Eq 'vlc_entry__(android_window|gles2|android_display)[,(]' "$modules"
if grep -Eq 'vlc_entry__(lua|smb2|access_smb|access_nfs|upnp|microdns|bluray|dvdread|live555|stream_out_[a-z_]+)[,(]' "$modules"; then
  echo 'An excluded TV module is still linked' >&2
  exit 1
fi
for library in libvlc.so libvlcjni.so libc++_shared.so; do
  native="$work/libvlc/jni/libs/$abi/$library"
  test -s "$native"
  cp "$native" "$output/$library"
done
cp "$modules" "$output/modules.c"
cp "$work/vlc/build-android-$triplet/config.log" "$output/config.log"
cp "$ANDROID_NDK/source.properties" "$output/ndk.properties"
git -C "$work/vlc" rev-parse HEAD > "$output/patched-vlc-revision.txt"
(cd "$output" && sha256sum ./*.so > SHA256SUMS)
echo "Built TV LibVLC for $abi. No APK was built."
