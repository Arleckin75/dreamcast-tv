#!/usr/bin/env bash
set -euo pipefail
mkdir -p .tools
export NDK_TEST_VERSION="$(tr -d '[:space:]' < .android-ndk-version)"
test_root="$(mktemp -d "$PWD/.tools/ndk-setup-test.XXXXXX")"
cleanup() {
  case "$test_root" in "$PWD"/.tools/ndk-setup-test.*) rm -rf -- "$test_root" ;; *) exit 1 ;; esac
}
trap cleanup EXIT
mkdir -p "$test_root/bin" "$test_root/sdk"
export ANDROID_SDK_ROOT="$test_root/sdk"
export PATH="$test_root/bin:$PATH"
cat > "$test_root/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$test_root/bin/sdkmanager" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
root="$ANDROID_SDK_ROOT"
version="$NDK_TEST_VERSION"
[[ "$1" == "--sdk_root=$root" && "$2" == --install && "$3" == "ndk;$version" ]]
count=0
if [[ -f "$root/attempts" ]]; then count="$(cat "$root/attempts")"; fi
count=$((count + 1))
echo "$count" > "$root/attempts"
[[ ! -e "$root/.temp/corrupt.zip" && ! -e "$root/ndk/$version/broken" ]]
if [[ "$count" == 1 ]]; then
  mkdir -p "$root/.temp" "$root/ndk/$version"
  touch "$root/.temp/corrupt.zip" "$root/ndk/$version/broken"
  exit 1
fi
ndk="$root/ndk/$version"
mkdir -p "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "Pkg.Revision = $version" > "$ndk/source.properties"
printf '#!/usr/bin/env bash\nexit 0\n' > "$ndk/ndk-build"
cp "$ndk/ndk-build" "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
chmod +x "$ndk/ndk-build" "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
MOCK
chmod +x "$test_root/bin/"*
bash scripts/install_android_ndk.sh
[[ "$(cat "$test_root/sdk/attempts")" == 2 ]]
bash scripts/install_android_ndk.sh
[[ "$(cat "$test_root/sdk/attempts")" == 2 ]]
mkdir -p "$test_root/failed-sdk"
export ANDROID_SDK_ROOT="$test_root/failed-sdk"
cat > "$test_root/bin/sdkmanager" <<'MOCK'
#!/usr/bin/env bash
echo attempt >> "$ANDROID_SDK_ROOT/attempts"
exit 1
MOCK
if bash scripts/install_android_ndk.sh; then
  echo 'Expected installation failure' >&2
  exit 1
fi
[[ "$(wc -l < "$test_root/failed-sdk/attempts")" == 3 ]]
echo 'NDK setup checks passed: retry, cleanup, reuse, failure after three attempts.'
