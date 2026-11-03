"""Packaging checks use synthetic ELF fixtures; they do not compile an APK."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

SPEC = importlib.util.spec_from_file_location('libvlc_tv', Path(__file__).resolve().parents[1] / 'package_libvlc_tv.py')
TV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TV)
AUDIT_SPEC = importlib.util.spec_from_file_location('av1_audit', Path(__file__).resolve().parents[1] / 'audit_av1_apk.py')
AUDIT = importlib.util.module_from_spec(AUDIT_SPEC)
AUDIT_SPEC.loader.exec_module(AUDIT)


def elf(abi, size):
    payload = bytearray(size)
    payload[:7] = b'\x7fELF' + bytes((TV.ABIS[abi][0], 1, 1))
    struct.pack_into('<HH', payload, 16, 3, TV.ABIS[abi][1])
    return bytes(payload)


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.baseline = self.root / 'original.aar'
        with zipfile.ZipFile(self.baseline, 'w') as archive:
            archive.writestr('classes.jar', b'unchanged Java JNI bindings')
            archive.writestr('AndroidManifest.xml', b'unchanged manifest')
            archive.writestr('res/values/values.xml', '<resources><string name="build_libvlc_revision">old</string><string name="build_vlc_revision">old</string></resources>')
            archive.writestr('assets/lua/playlist.lua', b'unused Lua')
            archive.writestr('jni/x86/libvlc.so', b'not an ARM target')
            for abi in TV.ABIS:
                for library in TV.LIBRARIES:
                    archive.writestr(f'jni/{abi}/{library}', elf(abi, 2048))
        self.pom = self.root / 'original.pom'
        self.pom.write_text(f'''<project xmlns="{TV.NAMESPACE}">
          <modelVersion>4.0.0</modelVersion><groupId>org.videolan.android</groupId>
          <artifactId>libvlc-all</artifactId><version>3.7.7</version><name>LibVLC</name>
          <dependencies><dependency><groupId>androidx.annotation</groupId>
          <artifactId>annotation</artifactId><version>1.7.1</version></dependency></dependencies>
          <licenses><license><name>original license</name></license></licenses></project>''')
        self.config = {'BASELINE_SHA256': hashlib.sha256(self.baseline.read_bytes()).hexdigest(),
                       'BASELINE_VERSION': '3.7.7', 'TV_VERSION': '3.7.7-tv1',
                       'LIBVLCJNI_REVISION': '0b8dc65', 'VLC_REVISION': '84177b2'}
        self.native = self.root / 'native'
        for abi in TV.ABIS:
            directory = self.native / abi
            directory.mkdir(parents=True)
            for library in TV.LIBRARIES:
                (directory / library).write_bytes(elf(abi, 1024))
            (directory / 'modules.c').write_text(''.join(f'vlc_entry__{module},\n' for module in
                ('dav1d', 'avcodec', 'adaptive', 'mp4', 'android_audiotrack', 'gles2')))
            self.refresh_checksums(abi)

    def refresh_checksums(self, abi):
        directory = self.native / abi
        (directory / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256((directory / library).read_bytes()).hexdigest()}  ./{library}\n'
            for library in TV.LIBRARIES))

    def package(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return TV.package(self.baseline, self.pom, self.native, self.root / 'maven', self.config)

    def test_replaces_both_arm_abis_preserves_bindings_and_dependencies(self):
        result = self.package()
        with zipfile.ZipFile(result) as archive:
            self.assertEqual(archive.read('classes.jar'), b'unchanged Java JNI bindings')
            self.assertEqual(len([name for name in archive.namelist() if name.startswith('jni/')]), 6)
            self.assertNotIn('jni/x86/libvlc.so', archive.namelist())
            self.assertNotIn('assets/lua/playlist.lua', archive.namelist())
            profile = json.loads(archive.read('assets/dreamcast-libvlc-tv.json'))
            self.assertEqual({row['abi'] for row in profile['armComparison']}, set(TV.ABIS))
            self.assertIn(b'0b8dc65-tv1', archive.read('res/values/values.xml'))
        pom = ET.parse(result.with_suffix('.pom'))
        self.assertEqual(pom.find(f'{{{TV.NAMESPACE}}}groupId').text, 'tv.dreamcast.libvlc')
        self.assertEqual(pom.find(f'.//{{{TV.NAMESPACE}}}dependency/{{{TV.NAMESPACE}}}artifactId').text, 'annotation')
        self.assertEqual(pom.find(f'.//{{{TV.NAMESPACE}}}license/{{{TV.NAMESPACE}}}name').text, 'original license')

    def test_rejects_unchanged_core_size(self):
        (self.native / 'armeabi-v7a/libvlc.so').write_bytes(elf('armeabi-v7a', 2048))
        self.refresh_checksums('armeabi-v7a')
        with self.assertRaisesRegex(ValueError, 'did not get smaller'):
            self.package()

    def test_rejects_wrong_abi(self):
        (self.native / 'arm64-v8a/libvlc.so').write_bytes(elf('armeabi-v7a', 1024))
        self.refresh_checksums('arm64-v8a')
        with self.assertRaisesRegex(ValueError, 'wrong ABI'):
            self.package()

    def test_rejects_corrupted_native_artifact(self):
        (self.native / 'arm64-v8a/libvlcjni.so').write_bytes(elf('arm64-v8a', 1025))
        with self.assertRaisesRegex(ValueError, 'Native artifact checksum'):
            self.package()

    def test_requires_av1_decoder(self):
        modules = self.native / 'arm64-v8a/modules.c'
        modules.write_text(modules.read_text().replace('vlc_entry__dav1d,', ''))
        with self.assertRaisesRegex(ValueError, 'dav1d'):
            self.package()

    def test_rejects_disabled_module_linked_back_in(self):
        modules = self.native / 'arm64-v8a/modules.c'
        modules.write_text(modules.read_text() + 'vlc_entry__lua,\n')
        with self.assertRaisesRegex(ValueError, 'Excluded VLC modules'):
            self.package()

    def test_rejects_wrong_original_aar(self):
        self.config['BASELINE_SHA256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Original LibVLC AAR checksum'):
            self.package()

    def audit_fixture(self, include_profile=True, unchanged_core=False, missing_track=False):
        aar = self.package()
        fixture = self.root / 'packaged-fixture.zip'
        with zipfile.ZipFile(aar) as source, zipfile.ZipFile(fixture, 'w') as target:
            for entry in source.infolist():
                if entry.filename.startswith('jni/'):
                    payload = source.read(entry)
                    if unchanged_core and entry.filename.endswith('/libvlc.so'):
                        payload = payload.ljust(2048, b'\0')
                    target.writestr(entry.filename.replace('jni/', 'lib/', 1), payload)
                elif include_profile and entry.filename == 'assets/dreamcast-libvlc-tv.json':
                    target.writestr(entry.filename, source.read(entry))
            target.writestr('classes.dex', b'\0'.join(
                f'Lorg/videolan/libvlc/{name};'.encode() for name in AUDIT.VLC_JNI_CLASSES
                if not (missing_track and name == 'interfaces/IMedia$Track')))
        dependencies = self.root / 'dependencies.txt'
        dependencies.write_text('+--- tv.dreamcast.libvlc:libvlc-tv:3.7.7-tv1\n')
        script = Path(__file__).resolve().parents[1] / 'audit_av1_apk.py'
        return subprocess.run([sys.executable, str(script), '--apk', str(fixture),
            '--dependencies', str(dependencies), '--player', 'software'],
            cwd=self.root, capture_output=True, text=True)

    def test_packaged_engine_audit_accepts_reduced_profile(self):
        result = self.audit_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_packaged_engine_audit_rejects_stripped_jni_track(self):
        result = self.audit_fixture(missing_track=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('missing JNI classes: interfaces/IMedia$Track', result.stdout)

    def test_packaged_engine_audit_rejects_missing_profile(self):
        result = self.audit_fixture(include_profile=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('profile is missing', result.stdout)

    def test_packaged_engine_audit_rejects_old_core(self):
        result = self.audit_fixture(unchanged_core=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('did not get smaller', result.stdout)


if __name__ == '__main__':
    unittest.main()
