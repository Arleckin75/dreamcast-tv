#!/usr/bin/env python3
"""Apply a temporary local dependency override ONLY in software_AV1 CI checkout.

Never commit the edited pubspec.yaml or pubspec.lock. Hardware builds still use
pub.dev's real video_player_android package.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
pubspec = root / 'pubspec.yaml'
text = pubspec.read_text(encoding='utf-8')
if 'dependency_overrides:' in text:
    raise SystemExit('Existing dependency_overrides detected: edit manually, do not overwrite')
text += '\n# CI-only software_AV1 override; never commit this modified pubspec.yaml.\n' \
        'dependency_overrides:\n' \
        '  video_player_android:\n' \
        '    path: third_party/video_player_android_noop\n'
pubspec.write_text(text, encoding='utf-8')
print('software_AV1 uses a no-op Android video_player plugin; ExoPlayer is excluded')
