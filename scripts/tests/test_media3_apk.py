"""Packaging audit regression tests; no Android SDK or native binaries required."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'audit_av1_apk.py'


def elf(arm64, alignment=16384):
    header, entry = (64, 56) if arm64 else (52, 32)
    data = bytearray(header + entry)
    data[:5] = b'\x7fELF' + bytes([2 if arm64 else 1])
    struct.pack_into('<H', data, 18, 183 if arm64 else 40)
    struct.pack_into('<Q' if arm64 else '<I', data, 32 if arm64 else 28, header)
    struct.pack_into('<HH', data, 54 if arm64 else 42, entry, 1)
    struct.pack_into('<I', data, header, 1)
    struct.pack_into('<Q' if arm64 else '<I', data, header + (48 if arm64 else 28), alignment)
    return bytes(data)


class Media3ApkTest(unittest.TestCase):
    def audit(self, player, extra=None, dependencies='', alignment=16384):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'test.apk'
            payload = {'classes.dex': b'Landroidx/media3/exoplayer/ExoPlayer;'}
            project = SCRIPT.parent.parent
            for name in ('media3-APACHE-2.0.txt', 'media3-NOTICES.txt'):
                payload[f'assets/licenses/{name}'] = (project / 'assets/licenses' / name).read_bytes()
            if player == 'unified':
                payload['classes.dex'] += b'Landroidx/media3/decoder/av1/Libdav1dVideoRenderer;'
                for abi in ('armeabi-v7a', 'arm64-v8a'):
                    payload[f'lib/{abi}/libdav1dJNI.so'] = elf(abi == 'arm64-v8a', alignment)
                for notice in ('dav1d-BSD-2-Clause.txt', 'dav1d-NOTICES.txt', 'cpu_features-APACHE-2.0.txt',
                               'cpu_features-NOTICES.txt', 'ndk-LLVM-NOTICE.txt'):
                    payload[f'assets/licenses/{notice}'] = (
                        project / 'native-android/app/src/main/assets/licenses' / notice
                    ).read_bytes()
            for key, value in (extra or {}).items():
                if value is None:
                    payload.pop(key, None)
                else:
                    payload[key] = value
            with zipfile.ZipFile(apk, 'w') as archive:
                for name, data in payload.items():
                    archive.writestr(name, data)
            report = root / 'dependencies.txt'
            report.write_text('androidx.media3:media3-exoplayer:1.9.2\n' + dependencies)
            result = subprocess.run(
                [sys.executable, str(SCRIPT), '--apk', str(apk), '--dependencies', str(report), '--player', player],
                cwd=root, text=True, capture_output=True,
            )
            return result.returncode, result.stdout + result.stderr

    def test_accepts_media3_hardware_and_dav1d_software(self):
        for player in ('unified',):
            with self.subTest(player=player):
                status, output = self.audit(player)
                self.assertEqual(0, status, output)

    def test_rejects_legacy_engines_and_software_library_in_hardware(self):
        for extra in ({'lib/arm64-v8a/libvlc.so': b''},
                      {'lib/arm64-v8a/libflutter.so': b''},
                      {'assets/flutter_assets/kernel_blob.bin': b''}):
            with self.subTest(extra=list(extra)):
                status, output = self.audit('unified', extra=extra)
                self.assertNotEqual(0, status, output)

    def test_rejects_vlc_dependency_without_packaged_classes(self):
        status, output = self.audit('unified', dependencies='org.videolan.android:libvlc-all:3.7.7')
        self.assertNotEqual(0, status, output)

    def test_rejects_unaligned_software_native_library(self):
        status, output = self.audit('unified', alignment=4096)
        self.assertNotEqual(0, status, output)

    def test_rejects_dependency_report_without_packaged_media3(self):
        status, output = self.audit('unified', extra={'classes.dex': b'Lcom/dreamcast/App;'})
        self.assertNotEqual(0, status, output)

    def test_rejects_missing_software_copyright_notices(self):
        status, output = self.audit('unified', extra={'assets/licenses/dav1d-NOTICES.txt': None})
        self.assertNotEqual(0, status, output)

    def test_rejects_bsd_text_without_original_copyright(self):
        status, output = self.audit('unified', extra={'assets/licenses/dav1d-BSD-2-Clause.txt': b'BSD-2-Clause'})
        self.assertNotEqual(0, status, output)


if __name__ == '__main__':
    unittest.main()
