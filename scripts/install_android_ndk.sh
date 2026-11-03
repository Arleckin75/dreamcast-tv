#!/usr/bin/env bash
# Install the pinned NDK before Gradle starts; retry incomplete SDK downloads.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_version="$(tr -d '[:space:]' < "$project_root/.android-ndk-version")"
[[ "$ndk_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo '::error::Invalid .android-ndk-version'
  exit 1
}
sdk_root="$(realpath "${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK path is missing}}")"
[[ "$sdk_root" != / ]] || { echo '::error::Invalid Android SDK path'; exit 1; }
ndk_dir="$sdk_root/ndk/$ndk_version"
sdk_temp="$sdk_root/.temp"

ndk_is_ready() {
  [[ -f "$ndk_dir/source.properties" ]] &&
    grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*${ndk_version//./\\.}[[:space:]]*$" "$ndk_dir/source.properties" &&
    [[ -x "$ndk_dir/ndk-build" ]] &&
    [[ -x "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]
}

if ndk_is_ready; then
  echo "NDK $ndk_version is already installed."
  exit 0
fi

sdk_manager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdk_manager" ]]; then
  sdk_manager="$(command -v sdkmanager)" || {
    echo '::error::Android SDK Manager is missing'
    exit 1
  }
fi

for attempt in 1 2 3; do
  # Each Actions job has its own SDK. Never remove the SDK root or other NDKs.
  [[ "$(realpath -m "$ndk_dir")" == "$ndk_dir" && "$(realpath -m "$sdk_temp")" == "$sdk_temp" ]] || {
    echo '::error::NDK cleanup paths resolve outside their expected locations'
    exit 1
  }
  rm -rf -- "$ndk_dir" "$sdk_temp"
  echo "Installing NDK $ndk_version (attempt $attempt/3)..."
  if timeout 600 "$sdk_manager" --sdk_root="$sdk_root" --install "ndk;$ndk_version"; then
    if ndk_is_ready; then
      echo "NDK $ndk_version installation verified."
      exit 0
    fi
  fi
  echo "::warning::NDK installation failed or is incomplete (attempt $attempt/3)."
  if [[ "$attempt" -lt 3 ]]; then sleep 5; fi
done

echo "::error::Could not install NDK $ndk_version after three attempts."
exit 1
