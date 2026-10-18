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
