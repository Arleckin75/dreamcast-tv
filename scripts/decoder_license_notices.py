"""Snapshot/verify notices from the pinned decoder sources; keep texts verbatim."""
from pathlib import Path
import re


def header_notices(root, files, source):
    groups = {}
    for path in sorted(files, key=lambda item: item.relative_to(root).as_posix()):
        text = path.read_text(encoding='utf-8')
        match = re.match(r'\s*(/\*.*?\*/|(?:;[^\n]*\n)+)', text, re.S)
        if match and 'copyright' in match[1].lower():
            groups.setdefault(match[1].strip(), []).append(path.relative_to(root).as_posix())
    if not groups:
        raise ValueError(f'No copyright notices found: {root}')
    parts = [f'Source: {source}\n\nOriginal source-header notices follow. Some files/architectures may not be linked in this APK.']
    for header, names in groups.items():
        parts.append('Files: ' + ', '.join(names) + '\n\n' + header)
    return '\n\n'.join(parts) + '\n'


def decoder_licenses(project, media):
    jni = media / 'libraries/decoder_av1/src/main/jni'
    common = Path('assets/licenses')
    software = Path('native-android/app/src/main/assets/licenses')
    result = {
        common / 'media3-APACHE-2.0.txt': (media / 'LICENSE').read_text(encoding='utf-8'),
        software / 'dav1d-BSD-2-Clause.txt': (jni / 'dav1d/COPYING').read_text(encoding='utf-8'),
        software / 'cpu_features-APACHE-2.0.txt': (jni / 'cpu_features/LICENSE').read_text(encoding='utf-8'),
    }
    for name, directories, url in [
        ('dav1d', ['src', 'include'], 'https://code.videolan.org/videolan/dav1d/-/tree/42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa'),
        ('cpu_features', ['src', 'include', 'ndk_compat'], 'https://github.com/google/cpu_features/tree/d3b2440fcfc25fe8e6d0d4a85f06d68e98312f5b'),
    ]:
        root = jni / name
        files = [p for directory in directories for p in (root / directory).rglob('*')
                 if p.suffix in ('.c', '.h', '.S', '.asm', '.inc')]
        result[software / f'{name}-NOTICES.txt'] = header_notices(root, files, url)
    wrapper = media / 'libraries/decoder_av1/src/main'
    files = list((wrapper / 'java').rglob('*.java')) + list((wrapper / 'jni').glob('*.cc')) + list((wrapper / 'jni').glob('*.h'))
    result[common / 'media3-NOTICES.txt'] = header_notices(
        wrapper, files, 'https://github.com/androidx/media/tree/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/decoder_av1'
    )
    return {project / path: text for path, text in result.items()}


def verify_decoder_licenses(project, media):
    for path, expected in decoder_licenses(project, media).items():
        if not path.is_file() or path.read_text(encoding='utf-8') != expected:
            raise SystemExit(f'Missing or stale decoder license/notice: {path}')
