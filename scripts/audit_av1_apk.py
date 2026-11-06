#!/usr/bin/env python3
"""Audit Media3 runtime dependencies and packaged hardware/software AV1 APKs."""
import argparse
from pathlib import Path
import re
import sys
import zipfile

from audit_dav1d_apk import audit as audit_dav1d


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path, required=True)
    parser.add_argument('--player', choices=['unified'], default='unified')
    args = parser.parse_args()
    dependencies = args.dependencies.read_text(encoding='utf-8', errors='replace')
    issues = []
    if re.search(r'(?:org\.videolan(?:\.android)?|tv\.dreamcast\.libvlc):', dependencies):
        issues.append('LibVLC dependency present')
    if 'androidx.media3:media3-exoplayer:' not in dependencies:
        issues.append('Media3 ExoPlayer dependency missing')
    with zipfile.ZipFile(args.apk) as apk:
        names = apk.namelist()
        dex = b''.join(apk.read(n) for n in names if re.fullmatch(r'classes(?:\d+)?\.dex', n))
        if not dex:
            issues.append('No classes.dex')
        if b'Landroidx/media3/exoplayer/' not in dex:
            issues.append('Media3 ExoPlayer classes missing')
        if any('libvlc' in n.lower() for n in names) or b'Lorg/videolan/libvlc/' in dex:
            issues.append('LibVLC packaged')
        if any(n.endswith(('/libflutter.so', '/libapp.so')) or n.startswith('assets/flutter_assets/') for n in names):
            issues.append('Flutter runtime or Dart assets packaged')
        if b'Lio/flutter/' in dex:
            issues.append('Flutter classes packaged')
        for notice in ('media3-APACHE-2.0.txt', 'media3-NOTICES.txt'):
            path = f'assets/licenses/{notice}'
            if path not in names or not apk.read(path).strip():
                issues.append('Missing/empty license: ' + notice)
        try:
            audit_dav1d(args.apk)
        except (AssertionError, KeyError, ValueError) as error:
            issues.append(str(error))
    report = [
        'Dream Cast TV - packaged AV1 engine audit',
        f'Variant: {args.player}', f'APK: {args.apk}',
        'RESULT: ' + ('FAIL' if issues else 'PASS'),
        *('ERROR: ' + issue for issue in issues),
    ]
    output = Path('dist') / f'av1-audit-{args.player}.txt'
    output.parent.mkdir(exist_ok=True)
    output.write_text('\n'.join(report) + '\n', encoding='utf-8')
    print('\n'.join(report))
    if issues:
        sys.exit(1)


if __name__ == '__main__':
    run()
