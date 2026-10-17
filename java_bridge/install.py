"""Install or restore the exact-build openwig Java bridge patch on Windows.

The original JAR is retained beside Bitwig's JAR. Run only after closing Bitwig.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from build import BRIDGE_CLASS, MAIN_CLASS, ORIGINAL_SHA256
import zipfile

DEFAULT_ROOT = Path(r"C:\Program Files\Bitwig Studio")
BACKUP_NAME = "bitwig.jar.openwig-original-6.1.5"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure_closed() -> None:
    result = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True,
                            text=True, check=True)
    names = {line.split(",", 1)[0].strip('"').lower() for line in result.stdout.splitlines()}
    running = names.intersection({"bitwig studio.exe", "bitwigstudioapp.exe",
                                  "bitwigaudioengine-x64-avx2.exe"})
    if running:
        raise RuntimeError("close Bitwig and its audio engine first: " + ", ".join(sorted(running)))


def replace_jar(source: Path, destination: Path) -> None:
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix="bitwig.jar.openwig-",
                                     suffix=".tmp", delete=False) as temporary:
        staged = Path(temporary.name)
    try:
        shutil.copy2(source, staged)
        os.replace(staged, destination)
    finally:
        staged.unlink(missing_ok=True)


def install(root: Path, patched: Path, *, check_processes: bool = True) -> None:
    if check_processes:
        ensure_closed()
    installed = root / "bin" / "bitwig.jar"
    backup = root / "bin" / BACKUP_NAME
    if sha256(installed) != ORIGINAL_SHA256:
        raise RuntimeError("installed bitwig.jar is not the verified 6.1.5 original")
    with zipfile.ZipFile(installed) as source, zipfile.ZipFile(patched) as target:
        source_names = set(source.namelist())
        target_names = set(target.namelist())
        if target.testzip() is not None or target_names - source_names != {BRIDGE_CLASS}:
            raise RuntimeError("patched JAR has unexpected contents")
        if source_names - target_names:
            raise RuntimeError("patched JAR removed original contents")
        changed = {name for name in source_names if source.read(name) != target.read(name)}
        if changed != {MAIN_CLASS}:
            raise RuntimeError(f"unexpected modified JAR entries: {sorted(changed)}")
    if backup.exists():
        if sha256(backup) != ORIGINAL_SHA256:
            raise RuntimeError("existing original backup has an unexpected hash")
    else:
        shutil.copy2(installed, backup)
        if sha256(backup) != ORIGINAL_SHA256:
            raise RuntimeError("backup verification failed")
    replace_jar(patched, installed)
    if sha256(installed) != sha256(patched):
        raise RuntimeError("installed JAR verification failed; restore the backup")
    print(f"installed bridge: {installed}")
    print(f"original retained: {backup}")


def restore(root: Path, *, check_processes: bool = True) -> None:
    if check_processes:
        ensure_closed()
    installed = root / "bin" / "bitwig.jar"
    backup = root / "bin" / BACKUP_NAME
    if sha256(backup) != ORIGINAL_SHA256:
        raise RuntimeError("original backup missing or hash mismatch")
    replace_jar(backup, installed)
    if sha256(installed) != ORIGINAL_SHA256:
        raise RuntimeError("restore verification failed")
    print(f"restored original: {installed}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["install", "restore", "status"])
    parser.add_argument("--bitwig-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--patched", type=Path)
    parser.add_argument("--assume-closed", action="store_true",
                        help="skip process check for an isolated, non-running test copy")
    args = parser.parse_args()
    root = args.bitwig_root
    installed = root / "bin" / "bitwig.jar"
    backup = root / "bin" / BACKUP_NAME
    if args.command == "status":
        print(f"installed SHA-256: {sha256(installed)}")
        print(f"original backup: {backup if backup.exists() else 'absent'}")
    elif args.command == "install":
        if args.patched is None:
            parser.error("install requires --patched")
        install(root, args.patched, check_processes=not args.assume_closed)
    else:
        restore(root, check_processes=not args.assume_closed)


if __name__ == "__main__":
    main()
