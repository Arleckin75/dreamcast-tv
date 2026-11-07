"""Prepare pinned Media3/dav1d sources and ARM static libraries (Python 3 + Meson + Ninja)."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
from decoder_license_notices import verify_decoder_licenses

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.tools' / 'media3-dav1d'
SOURCES = {
    'media': ('https://github.com/androidx/media.git', '8c6678b657ede1e7883fc164ef73ed483c7796c3'),
    'dav1d': ('https://code.videolan.org/videolan/dav1d.git', '42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa'),
    'cpu_features': ('https://github.com/google/cpu_features.git', 'd3b2440fcfc25fe8e6d0d4a85f06d68e98312f5b'),
}


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def checkout(name, target):
    url, revision = SOURCES[name]
    if not (target / '.git').exists():
        target.mkdir(parents=True, exist_ok=True)
        run('git', 'init', target)
        run('git', '-C', target, 'remote', 'add', 'origin', url)
        run('git', '-C', target, 'fetch', '--depth=1', 'origin', revision)
        run('git', '-C', target, 'checkout', '--detach', 'FETCH_HEAD')
    actual = subprocess.check_output(['git', '-C', str(target), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != revision:
        raise SystemExit(f'Unexpected {name} revision: {actual}')
    changes = subprocess.check_output(
        ['git', '-C', str(target), 'status', '--porcelain', '--untracked-files=no'], text=True
    ).strip()
    if changes:
        raise SystemExit(f'{name} has modified sources; use a clean pinned checkout: {target}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    args = parser.parse_args()
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
    if not toolchain.is_dir():
        parser.error(f'NDK toolchain missing: {toolchain}')
    media = WORK / 'media'
    checkout('media', media)
    jni = media / 'libraries/decoder_av1/src/main/jni'
    checkout('dav1d', jni / 'dav1d')
    checkout('cpu_features', jni / 'cpu_features')
    verify_decoder_licenses(ROOT, media)
    for abi, target, family, cpu in [
        ('arm64-v8a', 'aarch64-linux-android23', 'aarch64', 'aarch64'),
        ('armeabi-v7a', 'armv7a-linux-androideabi23', 'arm', 'armv7'),
    ]:
        build = WORK / f'build-{abi}'
        cross = WORK / f'{abi}.meson'
        # Invoke clang.exe directly: Windows .cmd compiler wrappers cannot be spawned by Meson.
        clang = (toolchain / ('clang.exe' if os.name == 'nt' else 'clang')).as_posix()
        suffix = '.exe' if os.name == 'nt' else ''
        cross.write_text(f"""[binaries]
c = ['{clang}', '--target={target}']
ar = '{(toolchain / ('llvm-ar' + suffix)).as_posix()}'
strip = '{(toolchain / ('llvm-strip' + suffix)).as_posix()}'
[host_machine]
system = 'android'
cpu_family = '{family}'
cpu = '{cpu}'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-fPIC']
""", encoding='utf-8')
        if not (build / 'build.ninja').exists():
            run(sys.executable, '-m', 'mesonbuild.mesonmain', 'setup', build, jni / 'dav1d',
                '--cross-file', cross, '--default-library=static', '--buildtype=release',
                '-Denable_tools=false', '-Denable_tests=false')
        run(sys.executable, '-m', 'mesonbuild.mesonmain', 'compile', '-C', build)
        output = jni / 'nativelib' / abi
        output.mkdir(parents=True, exist_ok=True)
        shutil.copy2(build / 'src/libdav1d.a', output / 'libdav1d.a')
    print('dav1d ARM libraries ready. Build the unified native-android app.')


if __name__ == '__main__':
    main()
