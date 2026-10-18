"""JavaSong wire-contract tests against a fake bridge (no Bitwig needed)."""

import pytest

from openwig.java_song import JavaSong


class FakeJavaBridge:
    """Answers the Java bridge handshake and records every request."""

    def __init__(self):
        self.calls = []
        self.version = "6.1.5"

    def start(self):
        pass

    def stop(self):
        pass

    def wait_connected(self, timeout):
        return True

    def request(self, method, params=None, timeout=None):
        params = params or {}
        self.calls.append((method, params))
        if method == "host.version":
            return {"version": self.version}
        if method == "document.status":
            return {"active": True}
        if method == "project.dump":
            return {"metadata": {}, "cue_markers": [], "main_tracks": []}
        if method == "project.export_dawproject":
            return {"path": params["path"], "bytes": 1, "embedded_files": 0}
        if method in {"track.read", "clip.read", "device.read"}:
            return {"path": params["path"], "method": method}
        if method == "automation.enumerate":
            return []
        raise AssertionError(f"unexpected request: {method}")


@pytest.fixture
def bridge():
    return FakeJavaBridge()


def test_dump_sends_read_only_project_dump(bridge):
    song = JavaSong(bridge=bridge)
    result = song.dump()
    assert ("project.dump", {}) in bridge.calls
    assert result == {"metadata": {}, "cue_markers": [], "main_tracks": []}


def test_dump_is_the_only_call_it_makes(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    song.dump()
    assert [method for method, _ in bridge.calls] == ["project.dump"]


def test_export_dawproject_sends_path_and_embed_flag(bridge):
    song = JavaSong(bridge=bridge)
    result = song.export_dawproject("out.dawproject", embed_media=True)
    method, params = bridge.calls[-1]
    assert method == "project.export_dawproject"
    assert params["embed_media"] is True
    # Relative paths resolve against the caller's cwd before crossing the wire.
    from pathlib import Path
    assert params["path"] == str(Path("out.dawproject").resolve())
    assert result["path"] == params["path"]


def test_refuses_wrong_host_version(bridge):
    bridge.version = "6.0.6"
    from openwig.bridge import BridgeError
    with pytest.raises(BridgeError):
        JavaSong(bridge=bridge)


def test_targeted_reads_address_nested_tracks_without_index_or_write(bridge):
    track = JavaSong(bridge=bridge).track_at("Drums/Kick")
    bridge.calls.clear()
    assert track.snapshot()["method"] == "track.read"
    assert track.read_clip(2)["method"] == "clip.read"
    assert track.read_device(1)["method"] == "device.read"
    assert track.enumerate_automation() == []
    assert bridge.calls == [
        ("track.read", {"path": "Drums/Kick"}),
        ("clip.read", {"path": "Drums/Kick", "clip": 2}),
        ("device.read", {"path": "Drums/Kick", "device": 1}),
        ("automation.enumerate", {"path": "Drums/Kick"}),
    ]
    with pytest.raises(ValueError, match="index-based"):
        track.volume(0.2)
    assert len(bridge.calls) == 4


@pytest.mark.parametrize("path", ["master", "fx/Reverb", "Leads/Vocals"])
def test_special_and_literal_slash_paths_are_preserved(bridge, path):
    track = JavaSong(bridge=bridge).track_at(path)
    track.read()
    assert bridge.calls[-1] == ("track.read", {"path": path})


@pytest.mark.parametrize("path", ["", "/Drums", "Drums/", None])
def test_empty_path_segments_are_rejected_before_rpc(bridge, path):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    with pytest.raises(ValueError, match="track path"):
        song.track_at(path)
    assert bridge.calls == []
