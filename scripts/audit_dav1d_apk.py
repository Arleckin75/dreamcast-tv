"""Verify the unified APK contains dav1d for both ARM ABIs and no VLC."""
import argparse
from pathlib import Path
import struct
import zipfile


def audit(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert not any('libvlc' in name.lower() for name in names), 'LibVLC packaged'
        dex = b''.join(apk.read(name) for name in names if name.endswith('.dex'))
        assert b'Lorg/videolan/libvlc/' not in dex, 'VLC classes packaged'
        assert b'Landroidx/media3/decoder/av1/Libdav1dVideoRenderer;' in dex, 'dav1d renderer missing'
        for abi in ('armeabi-v7a', 'arm64-v8a'):
            name = f'lib/{abi}/libdav1dJNI.so'
            elf = apk.read(name)
            assert elf[:4] == b'\x7fELF'
            is64 = elf[4] == 2
            assert is64 == (abi == 'arm64-v8a'), f'{name}: wrong ELF class'
            machine = struct.unpack_from('<H', elf, 18)[0]
            assert machine == (183 if is64 else 40), f'{name}: wrong machine {machine}'
            offset = struct.unpack_from('<Q' if is64 else '<I', elf, 32 if is64 else 28)[0]
            size, count = struct.unpack_from('<HH', elf, 54 if is64 else 42)
            for index in range(count):
                entry = offset + index * size
                kind = struct.unpack_from('<I', elf, entry)[0]
                if kind == 1:  # PT_LOAD
                    alignment = struct.unpack_from('<Q' if is64 else '<I', elf, entry + (48 if is64 else 28))[0]
                    assert alignment >= 16384, f'{name}: ELF alignment {alignment}'
            print(f'{abi}: dav1d JNI {len(elf):,} bytes, ELF alignment >= 16 KB')
        for license in ('media3-APACHE-2.0.txt', 'media3-NOTICES.txt', 'dav1d-BSD-2-Clause.txt',
                        'dav1d-NOTICES.txt', 'cpu_features-APACHE-2.0.txt', 'cpu_features-NOTICES.txt',
                        'ndk-LLVM-NOTICE.txt'):
            assert f'assets/licenses/{license}' in names, f'Missing {license}'
            assert apk.read(f'assets/licenses/{license}').strip(), f'Empty {license}'
        bsd = apk.read('assets/licenses/dav1d-BSD-2-Clause.txt')
        for required in (b'VideoLAN and dav1d authors', b'Redistribution and use', b'THIS SOFTWARE IS PROVIDED'):
            assert required in bsd, 'Incomplete dav1d BSD copyright/license/disclaimer'
    print(f'PASS: {path} ({Path(path).stat().st_size:,} bytes), no LibVLC')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    audit(parser.parse_args().apk)
