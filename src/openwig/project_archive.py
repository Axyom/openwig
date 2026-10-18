"""Portable Bitwig 6.1.5 native snapshots, independent of the live bridge."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

FORMAT = "openwig-project-1"
BITWIG_VERSION = "6.1.5"


def _hash(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pack_project(project, path) -> dict:
    """Bundle a self-contained native snapshot directory; refuse overwrites.

    The directory must contain only the project and its collected resources.
    JavaSong.export_project creates this directory using the native writer.
    """
    project, path = Path(project).resolve(), Path(path).resolve()
    if project.suffix.lower() != ".bwproject" or not project.is_file():
        raise ValueError("a native .bwproject file is required")
    root = project.parent
    if path.is_relative_to(root):
        raise ValueError("archive destination must be outside the project directory")
    files = sorted(root.rglob("*"))
    if any(file.is_symlink() for file in files):
        raise ValueError("project resources must not be symbolic links")
    files = [file for file in files if file.is_file()]
    if any(file.relative_to(root).as_posix() == "manifest.json" for file in files):
        raise ValueError("reserved project resource name: manifest.json")
    manifest = dict(format=FORMAT, bitwig_version=BITWIG_VERSION,
                    project=project.name,
                    files={file.relative_to(root).as_posix():
                           dict(bytes=file.stat().st_size, sha256=_hash(file))
                           for file in files})
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also protects against an overwrite race.
    with path.open("xb") as output:
        try:
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED,
                                 allowZip64=True) as archive:
                archive.writestr("manifest.json", json.dumps(manifest, sort_keys=True))
                for file in files:
                    archive.write(file, file.relative_to(root).as_posix())
        except BaseException:
            output.close()
            path.unlink()
            raise
    return dict(path=str(path), bytes=path.stat().st_size, files=len(files))


def _safe_name(name: str) -> bool:
    parts = PurePosixPath(name).parts
    return (bool(parts) and not name.startswith("/") and "\\" not in name
            and all(part not in {"", ".", ".."} and ":" not in part for part in name.split("/")))


def write_project(path, directory) -> dict:
    """Verify and materialize a native archive into a fresh directory.

    This does not require a running Bitwig instance or overwrite any project.
    Third-party plugins and licensed packages must be installed separately.
    """
    path, directory = Path(path).resolve(), Path(directory).resolve()
    if directory.exists():
        raise FileExistsError(f"project destination already exists: {directory}")
    directory.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(not _safe_name(name) for name in names):
            raise ValueError("duplicate or unsafe archive resource path")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("format") != FORMAT or manifest.get("bitwig_version") != BITWIG_VERSION:
            raise ValueError("unsupported native project archive format or Bitwig version")
        files, project = manifest["files"], manifest["project"]
        if (set(names) != set(files) | {"manifest.json"} or project not in files
                or not project.lower().endswith(".bwproject") or ".bitwig-project" not in files):
            raise ValueError("incomplete native project archive")
        staging = Path(tempfile.mkdtemp(prefix=".openwig-", dir=directory.parent))
        try:
            for name, expected in files.items():
                info = archive.getinfo(name)
                if info.file_size != expected["bytes"]:
                    raise ValueError(f"resource size mismatch: {name}")
                target = staging.joinpath(*PurePosixPath(name).parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                digest = hashlib.sha256()
                with archive.open(name) as source, target.open("xb") as output:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
                        digest.update(chunk)
                if digest.hexdigest() != expected["sha256"]:
                    raise ValueError(f"resource hash mismatch: {name}")
            # Reserve the destination so concurrent restores cannot overwrite it.
            directory.mkdir()
            try:
                for item in staging.iterdir():
                    os.rename(item, directory / item.name)
            except BaseException:
                shutil.rmtree(directory)
                raise
        finally:
            shutil.rmtree(staging)
    return dict(path=str(directory / project), directory=str(directory), files=len(files))
