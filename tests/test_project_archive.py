import json
from pathlib import Path
import zipfile

import pytest

from openwig.project_archive import pack_project, write_project


def snapshot(tmp_path):
    root = tmp_path / "source"
    root.mkdir()
    (root / ".bitwig-project").touch()
    project = root / "song.bwproject"
    project.write_bytes(b"native document with embedded plugin state")
    (root / "samples").mkdir()
    (root / "samples" / "audio.wav").write_bytes(bytes(range(256)) * 100)
    return project


def test_portable_native_archive_roundtrip(tmp_path):
    project = snapshot(tmp_path)
    archive = tmp_path / "song.openwig.zip"
    result = pack_project(project, archive)
    assert result["files"] == 3
    restored = write_project(archive, tmp_path / "restored")
    assert Path(restored["path"]).read_bytes() == project.read_bytes()
    assert (tmp_path / "restored/samples/audio.wav").read_bytes() == (project.parent / "samples/audio.wav").read_bytes()
    assert (tmp_path / "restored/.bitwig-project").is_file()
    with pytest.raises(FileExistsError):
        pack_project(project, archive)
    with pytest.raises(FileExistsError):
        write_project(archive, tmp_path / "restored")


@pytest.mark.parametrize("failure", ["hash", "version", "missing", "traversal", "duplicate"])
def test_reject_damaged_or_unsafe_archives_without_partial_destination(tmp_path, failure):
    project = snapshot(tmp_path)
    archive = tmp_path / "valid.zip"
    pack_project(project, archive)
    with zipfile.ZipFile(archive) as source:
        entries = {name: source.read(name) for name in source.namelist()}
    manifest = json.loads(entries["manifest.json"])
    if failure == "hash":
        entries["song.bwproject"] = b"X" * len(entries["song.bwproject"])
    elif failure == "version":
        manifest["bitwig_version"] = "other"
    elif failure == "missing":
        del entries["samples/audio.wav"]
    elif failure == "traversal":
        entries["../escaped"] = b"bad"
    entries["manifest.json"] = json.dumps(manifest).encode()
    damaged = tmp_path / "damaged.zip"
    with zipfile.ZipFile(damaged, "w") as output:
        for name, data in entries.items():
            output.writestr(name, data)
        if failure == "duplicate":
            with pytest.warns(UserWarning):
                output.writestr("song.bwproject", b"duplicate")
    with pytest.raises(ValueError):
        write_project(damaged, tmp_path / "destination")
    assert not (tmp_path / "destination").exists()
    assert not list(tmp_path.glob(".openwig-*"))
    assert not (tmp_path / "escaped").exists()
