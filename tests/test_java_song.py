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
        if method == "project.import_dawproject":
            return {"path": params["path"], "main_tracks": 2, "effect_tracks": 1, "scenes": 4}
        if method == "project.set_metadata":
            return {k.lower(): str(v) for k, v in params.items()}
        if method == "cue_marker.create":
            return {"count": 1, "time": params["beat"], "name": params["name"], "color": params.get("color")}
        if method == "scene.update":
            return {"index": params["index"], "name": params.get("name", ""), "comment": params.get("comment", ""), "color": params.get("color")}
        if method == "track.create_group":
            path = f"{params['parent']}/{params['name']}" if params.get("parent") else params["name"]
            return {"count": 1, "index": 0, "name": params["name"], "path": path, "is_group": True}
        if method == "track.create" and params.get("parent"):
            path = f"{params['parent']}/{params['name']}"
            return {"count": 1, "index": 0, "name": params["name"], "path": path, "is_group": False}
        if method in {"track.set_volume", "track.set_pan", "track.set_mute", "track.set_solo",
                      "track.set_comment", "track.set_active", "track.set_destination",
                      "track.rename", "track.set_color", "track.set_monitor", "track.set_send"}:
            return {"path": params.get("path"), "index": params.get("index", 0), "name": params.get("name", "Track")}
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


def test_import_dawproject_sends_resolved_path(bridge):
    from pathlib import Path
    song = JavaSong(bridge=bridge)
    result = song.import_dawproject("in.dawproject")
    method, params = bridge.calls[-1]
    assert method == "project.import_dawproject"
    assert params["path"] == str(Path("in.dawproject").resolve())
    assert result["main_tracks"] == 2


def test_metadata_cue_marker_and_scene_updates(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    meta = song.set_metadata(title="My Track", artist="Openwig")
    marker = song.add_cue_marker(16.0, "Drop", color="#ff00aa")
    scene = song.update_scene(0, name="Intro", comment="Warm pad", color=(0.2, 0.4, 0.8))
    assert meta == {"title": "My Track", "artist": "Openwig"}
    assert marker["name"] == "Drop"
    assert scene["comment"] == "Warm pad"
    assert bridge.calls == [
        ("project.set_metadata", {"title": "My Track", "artist": "Openwig"}),
        ("cue_marker.create", {"beat": 16.0, "name": "Drop", "color": "#ff00aa"}),
        ("scene.update", {"index": 0, "name": "Intro", "comment": "Warm pad",
                          "r": 0.2, "g": 0.4, "b": 0.8, "a": 1.0}),
    ]


def test_group_track_and_nested_track_mutations(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    drums = song.group_track("Drums")
    kick = song.track("Kick", kind="instrument", parent=drums)
    kick.solo(True).comment("808 sub").active(False).route_to(drums)
    assert drums.path == "Drums"
    assert kick.path == "Drums/Kick"
    assert bridge.calls == [
        ("track.create_group", {"name": "Drums"}),
        ("track.create", {"type": "instrument", "index": -1, "name": "Kick", "parent": "Drums"}),
        ("track.set_solo", {"path": "Drums/Kick", "on": True}),
        ("track.set_comment", {"path": "Drums/Kick", "comment": "808 sub"}),
        ("track.set_active", {"path": "Drums/Kick", "on": False}),
        ("track.set_destination", {"path": "Drums/Kick", "destination": "Drums"}),
    ]


def test_refuses_wrong_host_version(bridge):
    bridge.version = "6.0.6"
    from openwig.bridge import BridgeError
    with pytest.raises(BridgeError):
        JavaSong(bridge=bridge)


def test_targeted_reads_and_path_edits_address_nested_tracks(bridge):
    track = JavaSong(bridge=bridge).track_at("Drums/Kick")
    bridge.calls.clear()
    assert track.snapshot()["method"] == "track.read"
    assert track.read_clip(2)["method"] == "clip.read"
    assert track.read_device(1)["method"] == "device.read"
    assert track.enumerate_automation() == []
    track.volume(0.2)
    assert bridge.calls == [
        ("track.read", {"path": "Drums/Kick"}),
        ("clip.read", {"path": "Drums/Kick", "clip": 2}),
        ("device.read", {"path": "Drums/Kick", "device": 1}),
        ("automation.enumerate", {"path": "Drums/Kick"}),
        ("track.set_volume", {"path": "Drums/Kick", "value": 0.2}),
    ]
    with pytest.raises(ValueError, match="index-based"):
        _ = track.idx


def test_renamed_root_group_supplies_current_parent_path(bridge):
    song = JavaSong(bridge=bridge)
    group = song.group_track("Drums/Bus")
    group.rename("Rhythm")
    bridge.calls.clear()
    song.audio_track("Hat", parent=group)
    assert bridge.calls == [
        ("track.create", {"type": "audio", "index": -1, "name": "Hat", "parent": "Rhythm"}),
    ]


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
