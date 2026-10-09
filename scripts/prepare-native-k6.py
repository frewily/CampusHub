#!/usr/bin/env python3
"""Prepare the pinned official macOS ARM64 k6 release in a private temp directory."""
import hashlib
from pathlib import Path
import platform
import subprocess
import tempfile
import zipfile

ARCHIVE_URL = "https://github.com/grafana/k6/releases/download/v1.3.0/k6-v1.3.0-macos-arm64.zip"
# Published asset digest from the official GitHub v1.3.0 release API, verified 2026-10-09.
ARCHIVE_SHA256 = "eb06b22418e26f7394023e53aaaddf0bec739f669acf718cc9b0e2d7f12bd7be"


def extract_verified(archive, destination):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        raise ValueError("official k6 archive checksum mismatch")
    with zipfile.ZipFile(archive) as source:
        entry = source.getinfo("k6-v1.3.0-macos-arm64/k6")
        if entry.file_size <= 0 or entry.file_size > 128 * 1024 * 1024:
            raise ValueError("unexpected k6 binary size")
        # Extract exactly one known member, never arbitrary archive paths or symlinks.
        with destination.open("xb") as target:
            target.write(source.read(entry))
    destination.chmod(0o700)


def main():
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("this pinned native baseline supports macOS ARM64 only")
    folder = Path(tempfile.mkdtemp(prefix="campushub-native-k6-", dir="/private/tmp"))
    folder.chmod(0o700)
    archive = folder / "official-release.zip"
    subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--max-time", "120",
                    "--output", str(archive), ARCHIVE_URL], check=True)
    archive.chmod(0o600)
    binary = folder / "k6"
    extract_verified(archive, binary)
    print(binary)


if __name__ == "__main__":
    main()
