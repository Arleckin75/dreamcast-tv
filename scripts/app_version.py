#!/usr/bin/env python3
"""Dream Cast TV release version numbers.

Releases are numbered X.Y.Z with each part 0-9: 0.9.5 -> 0.9.6 ... 0.9.9 -> 1.0.0,
1.9.9 -> 2.0.0. The latest release is the highest `vX.Y.Z` git tag; only the release
workflow takes the next number. Develop and debug builds reuse the latest one.

versionCode = X * 1_000_000 + Y * 100_000 + Z * 1000 + 1, so 0.9.5 is 905001 and every
release installs over the previous one. native-android/app/build.gradle.kts uses the same
formula; the release workflow checks that the APK agrees with this script.

Usage:
  app_version.py latest            latest release version from git tags
  app_version.py next [VERSION]    next release version, or VERSION after checking it is newer
  app_version.py code VERSION      versionCode of VERSION
"""
from __future__ import annotations

import re
import subprocess
import sys

VERSION = re.compile(r"^(\d+)\.(\d)\.(\d)$")
TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def parse(version: str) -> tuple[int, int, int]:
    """Parts of an X.Y.Z release version with Y and Z in 0..9."""
    match = VERSION.match(version.strip())
    if not match:
        raise ValueError(f"not a release version X.Y.Z with Y and Z in 0-9: {version!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def next_version(version: str) -> str:
    major, minor, patch = parse(version)
    patch += 1
    if patch > 9:
        patch, minor = 0, minor + 1
    if minor > 9:
        minor, major = 0, major + 1
    return f"{major}.{minor}.{patch}"


def version_code(version: str) -> int:
    major, minor, patch = parse(version)
    return major * 1_000_000 + minor * 100_000 + patch * 1000 + 1


def latest(tags: list[str]) -> str:
    """Highest vX.Y.Z tag as X.Y.Z; other tags are ignored."""
    versions = [tuple(map(int, m.groups())) for t in tags if (m := TAG.match(t.strip()))]
    if not versions:
        raise ValueError("no vX.Y.Z release tags found")
    return ".".join(map(str, max(versions)))


def git_tags() -> list[str]:
    output = subprocess.check_output(["git", "tag", "--list", "v*"], text=True)
    return output.split()


def checked_override(requested: str, current: str) -> str:
    """A manually chosen next version must be a valid release number above the latest."""
    if parse(requested) <= parse(current):
        raise ValueError(f"{requested} is not newer than the latest release {current}")
    return requested


def main(argv: list[str]) -> int:
    if not argv or argv[0] not in {"latest", "next", "code"}:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        if argv[0] == "code":
            print(version_code(argv[1]))
        elif argv[0] == "latest":
            print(latest(git_tags()))
        else:
            current = latest(git_tags())
            requested = argv[1].strip() if len(argv) > 1 and argv[1].strip() else ""
            print(checked_override(requested, current) if requested else next_version(current))
    except (ValueError, IndexError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
