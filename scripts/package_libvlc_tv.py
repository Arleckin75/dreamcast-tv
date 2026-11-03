#!/usr/bin/env python3
"""Package rebuilt ARM libraries with the pinned official JNI Java bindings."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183)}
LIBRARIES = ('libvlc.so', 'libvlcjni.so', 'libc++_shared.so')
NAMESPACE = 'http://maven.apache.org/POM/4.0.0'


def versions():
    path = Path(__file__).resolve().parent.parent / 'third_party/libvlc_tv/versions.env'
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if line and not line.startswith('#'))


def check_elf(payload, abi):
    elf_class, machine = ABIS[abi]
    if (len(payload) < 64 or payload[:4] != b'\x7fELF' or
            payload[4:7] != bytes((elf_class, 1, 1)) or
            struct.unpack_from('<HH', payload, 16) != (3, machine)):
        raise ValueError(f'Invalid shared library or wrong ABI: {abi}')


def check_modules(text):
    modules = set(re.findall(r'\bvlc_entry__([a-zA-Z0-9_]+)\s*[,\(]', text))
    for required in ('dav1d', 'avcodec', 'adaptive', 'mp4'):
        if required not in modules:
            raise ValueError(f'Required VLC module missing: {required}')
    if not modules.intersection(('android_audiotrack', 'opensles_android')):
        raise ValueError('Android audio output missing')
    if not modules.intersection(('android_window', 'gles2', 'android_display')):
        raise ValueError('Android video output missing')
    forbidden = re.compile(r'^(?:lua|smb2|access_smb|access_nfs|upnp|microdns|bluray|dvdread|live555|stream_out_.*)$')
    unexpected = sorted(module for module in modules if forbidden.fullmatch(module))
    if unexpected:
        raise ValueError('Excluded VLC modules still linked: ' + ', '.join(unexpected))
    return sorted(modules)


def package(baseline, pom, native_dir, output, config):
    baseline = Path(baseline)
    if hashlib.sha256(baseline.read_bytes()).hexdigest() != config['BASELINE_SHA256']:
        raise ValueError('Original LibVLC AAR checksum mismatch')
    original_pom = ET.parse(pom)
    root = original_pom.getroot()
    def node(name):
        result = root.find(f'{{{NAMESPACE}}}{name}')
        if result is None:
            raise ValueError(f'Missing Maven POM field: {name}')
        return result
    if (node('groupId').text, node('artifactId').text, node('version').text) != (
            'org.videolan.android', 'libvlc-all', config['BASELINE_VERSION']):
        raise ValueError('Original Maven POM coordinates mismatch')

    native_payloads = {}
    comparison = []
    with zipfile.ZipFile(baseline) as original:
        if len(original.namelist()) != len(set(original.namelist())):
            raise ValueError('Duplicate AAR entries')
        for abi in ABIS:
            directory = Path(native_dir) / abi
            modules = check_modules((directory / 'modules.c').read_text())
            checksums = dict((name.removeprefix('./'), digest) for digest, name in
                             (line.split() for line in (directory / 'SHA256SUMS').read_text().splitlines()))
            entries = []
            for library in LIBRARIES:
                payload = (directory / library).read_bytes()
                check_elf(payload, abi)
                digest = hashlib.sha256(payload).hexdigest()
                if checksums.get(library) != digest:
                    raise ValueError(f'Native artifact checksum mismatch: {abi}/{library}')
                old = original.getinfo(f'jni/{abi}/{library}').file_size
                if library == 'libvlc.so' and len(payload) >= old:
                    raise ValueError(f'libvlc.so did not get smaller for {abi}: {old} -> {len(payload)}')
                entries.append({'library': library, 'originalBytes': old,
                                'tvBytes': len(payload), 'sha256': digest})
                native_payloads[f'jni/{abi}/{library}'] = payload
            comparison.append({'abi': abi, 'libraries': entries, 'modules': modules})

        version = config['TV_VERSION']
        destination = Path(output) / 'tv/dreamcast/libvlc/libvlc-tv' / version
        destination.mkdir(parents=True, exist_ok=True)
        aar = destination / f'libvlc-tv-{version}.aar'
        with zipfile.ZipFile(aar, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as reduced:
            for entry in original.infolist():
                if entry.filename.startswith(('jni/', 'assets/lua/')):
                    continue
                if entry.filename == 'res/values/values.xml':
                    resources = ET.fromstring(original.read(entry))
                    for resource in resources.findall('string'):
                        if resource.get('name') == 'build_libvlc_revision':
                            resource.text = config['LIBVLCJNI_REVISION'][:7] + '-tv1'
                        if resource.get('name') == 'build_vlc_revision':
                            resource.text = config['VLC_REVISION'][:10] + '-tv1'
                    reduced.writestr(entry, ET.tostring(resources, encoding='utf-8', xml_declaration=True))
                    continue
                reduced.writestr(entry, original.read(entry))
            for name, payload in sorted(native_payloads.items()):
                reduced.writestr(name, payload)
            reduced.writestr('assets/dreamcast-libvlc-tv.json', json.dumps({
                'version': version, 'sourceRevisions': config,
                'armComparison': comparison,
            }, indent=2))
        with zipfile.ZipFile(aar) as reduced:
            if reduced.read('classes.jar') != original.read('classes.jar'):
                raise ValueError('JNI Java bindings changed unexpectedly')
            packaged_native = {name for name in reduced.namelist() if name.startswith('jni/')}
            if packaged_native != set(native_payloads):
                raise ValueError('Unexpected native libraries in the reduced AAR')

    node('groupId').text = 'tv.dreamcast.libvlc'
    node('artifactId').text = 'libvlc-tv'
    node('version').text = version
    node('name').text = 'Dream Cast TV reduced LibVLC'
    # Preserve the original dependency and license declarations.
    ET.register_namespace('', NAMESPACE)
    pom_output = destination / f'libvlc-tv-{version}.pom'
    original_pom.write(pom_output, encoding='utf-8', xml_declaration=True)
    for artifact in (aar, pom_output):
        artifact.with_suffix(artifact.suffix + '.sha256').write_text(
            hashlib.sha256(artifact.read_bytes()).hexdigest() + '\n')
    report = {'coordinates': f'tv.dreamcast.libvlc:libvlc-tv:{version}',
              'sourceRevisions': config, 'armComparison': comparison,
              'aarSha256': hashlib.sha256(aar.read_bytes()).hexdigest(),
              'note': 'Comparison excludes x86. Native byte savings are not a measurement of final APK savings.'}
    report_path = Path(output) / 'libvlc-tv-comparison.json'
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return aar


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ('baseline', 'pom', 'native-dir', 'output'):
        parser.add_argument('--' + argument, type=Path, required=True)
    args = parser.parse_args()
    package(args.baseline, args.pom, args.native_dir, args.output, versions())
