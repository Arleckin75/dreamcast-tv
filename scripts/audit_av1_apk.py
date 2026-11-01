#!/usr/bin/env python3
"""Audit packaged AV1 engines in Debug APK and Gradle runtime deps.

Fails closed when forbidden engine classes or dependencies are present.
"""
import argparse
import pathlib
import re
import sys
import zipfile


def run():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', type=pathlib.Path, required=True)
    ap.add_argument('--dependencies', type=pathlib.Path, required=True)
    ap.add_argument('--player', choices=['software', 'hardware'], required=True)
    args = ap.parse_args()
    issues = []
    if not args.apk.is_file():
        sys.exit('ERROR: APK not found: ' + str(args.apk))
    if not args.dependencies.is_file():
        sys.exit('ERROR: Gradle dependency report missing')
    dep = args.dependencies.read_text(encoding='utf-8', errors='replace')
    # Check real resolved module coordinates; not generic mention of video_player.
    vlc_deps = re.findall(r'(?im)^\s*[+\|\\ -]*[+\\-]---\s+org\.videolan:[^\s]+', dep)
    exo_deps = re.findall(r'(?im)^\s*[+\|\\ -]*[+\\-]---\s+(?:androidx\.media3:media3-exoplayer[^\s]*|com\.google\.android\.exoplayer:exoplayer[^\s]*)', dep)
    # Simpler fallback detects if Gradle output formatting changes.
    if not vlc_deps:
        vlc_deps = re.findall(r'(?im)org\.videolan(?:\.android)?:libvlc[^\s]*', dep)
    if not vlc_deps:
        vlc_deps = re.findall(r"(?im)org\.videolan(?:\.android)?:[^\s]+libvlc[^\s]*", dep)
    if not exo_deps:
        exo_deps = re.findall(r'(?im)(?:androidx\.media3:media3-exoplayer[^\s]*|com\.google\.android\.exoplayer:exoplayer[^\s]*)', dep)
    with zipfile.ZipFile(args.apk) as z:
        names = z.namelist()
        vlc_native = [n for n in names if re.search(r'/libvlc(?:jni)?\.so$', n, re.I)]
        dex_names = [n for n in names if re.fullmatch(r'classes(?:\d+)?\.dex', n)]
        if not dex_names:
            issues.append('No classes.dex found in APK')
        exo_dex_hits = []
        vlc_dex_hits = []
        for n in dex_names:
            payload = z.read(n)
            if any(x in payload for x in (b'Landroidx/media3/exoplayer/', b'Lcom/google/android/exoplayer2/')):
                exo_dex_hits.append(n)
            if b'Lorg/videolan/libvlc/' in payload:
                vlc_dex_hits.append(n)
    if args.player == 'software':
        if not vlc_native:
            issues.append('software_AV1: libvlc.so is MISSING')
        if not vlc_deps:
            issues.append('software_AV1: LibVLC runtime dependency is MISSING')
        if exo_deps:
            issues.append('software_AV1: forbidden ExoPlayer runtime dependency PRESENT')
        if exo_dex_hits:
            issues.append('software_AV1: forbidden ExoPlayer class descriptors PRESENT in DEX')
    else:
        if vlc_native:
            issues.append('hardware_AV1: forbidden LibVLC native libraries PRESENT')
        if vlc_deps:
            issues.append('hardware_AV1: forbidden LibVLC runtime dependency PRESENT')
        if vlc_dex_hits:
            issues.append('hardware_AV1: forbidden LibVLC class descriptors PRESENT in DEX')
        if not exo_deps:
            issues.append('hardware_AV1: ExoPlayer runtime dependency MISSING')
        if not exo_dex_hits:
            issues.append('hardware_AV1: ExoPlayer class descriptors MISSING in DEX')
    report = [
        'Dream Cast TV v0.7.1 - packaged AV1 engine audit',
        f'Variant: {args.player}',
        f'APK: {args.apk}',
        f'LibVLC .so: {len(vlc_native)} ({", ".join(vlc_native) if vlc_native else "none"})',
        f'LibVLC Gradle dependency matches: {len(vlc_deps)}',
        f'ExoPlayer Gradle dependency matches: {len(exo_deps)}',
        f'ExoPlayer DEX descriptors in: {", ".join(exo_dex_hits) if exo_dex_hits else "none"}',
        f'LibVLC DEX descriptors in: {", ".join(vlc_dex_hits) if vlc_dex_hits else "none"}',
        'RESULT: ' + ('FAIL' if issues else 'PASS'),
    ]
    report.extend('ERROR: ' + x for x in issues)
    report_path = pathlib.Path('dist') / f'av1-audit-{args.player}.txt'
    report_path.parent.mkdir(exist_ok=True)
    report_path.write_text('\n'.join(report)+'\n', encoding='utf-8')
    print('\n'.join(report))
    if issues:
        sys.exit(1)

if __name__ == '__main__':
    run()
