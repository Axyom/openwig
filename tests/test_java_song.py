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
        if method in {"document.info", "document.select", "project.save_copy", "project.duplicate_range", "project.insert_time", "project.delete_range"}:
            return params
        if method == "document.list":
            return {"documents": []}
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
        if method in {"clip.update", "clip.delete", "clip.import_audio", "clip.copy", "clip.split", "clip.replace_notes", "audio_event.import", "audio_event.update", "audio_event.delete"}:
            return params
        raise AssertionError(f"unexpected request: {method}")


@pytest.fixture
def bridge():
    return FakeJavaBridge()


def test_clip_edits_and_audio_import_wire_contract(bridge, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Group/Audio")
    source = tmp_path / "tone.wav"
    source.write_bytes(b"fixture")
    bridge.calls.clear()
    changes = dict(start=8, duration=4, loop=True, loop_start=.5,
                   loop_duration=2, play_start_offset=.25, muted=False,
                   name="Edited", color="#aa55ee", fade_in=.25, fade_out=.5)
    track.update_clip(2, **changes)
    track.delete_clip(1)
    track.import_audio(source, start=16, source_offset=.5, gain_db=-6,
                       name="Tone")
    assert bridge.calls == [
        ("clip.update", {"path": "Group/Audio", "clip": 2, "changes": changes}),
        ("clip.delete", {"path": "Group/Audio", "clip": 1}),
        ("clip.import_audio", {"path": "Group/Audio", "file": str(source.resolve()),
                               "start": 16, "source_offset": .5, "gain_db": -6,
                               "name": "Tone"}),
    ]


@pytest.mark.parametrize("index", [-1, True, 1.5, "1"])
def test_clip_edits_reject_invalid_indexes_without_rpc(bridge, index):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.delete_clip(index)
    with pytest.raises(ValueError):
        track.update_clip(index, name="invalid")
    assert bridge.calls == []


@pytest.mark.parametrize("changes", [dict(), dict(unknown=1), dict(start=-1),
    dict(duration=0), dict(loop_duration=-1), dict(fade_in=-1),
    dict(start=float("nan")), dict(duration=float("inf")), dict(loop=1),
    dict(muted="yes"), dict(name=2), dict(start=True)])
def test_clip_changes_rejected_before_rpc(bridge, changes):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_clip(0, **changes)
    assert bridge.calls == []


@pytest.mark.parametrize("kwargs", [dict(start=-1), dict(source_offset=-1),
    dict(duration=0), dict(gain_db=float("nan")), dict(gain_db=True), dict(name=42)])
def test_audio_import_rejects_invalid_values_without_rpc(bridge, tmp_path, kwargs):
    source = tmp_path / "tone.wav"
    source.write_bytes(b"fixture")
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.import_audio(source, **kwargs)
    assert bridge.calls == []


def test_native_clip_copy_split_and_note_replacement_contract(bridge):
    song = JavaSong(bridge=bridge)
    source = song.track_at("Group/MIDI")
    destination = song.track_at("Group/Copy")
    notes = [dict(key=64, duration=2, channel=3, start=.25, on_velocity=.6,
                  off_velocity=.2, muted=False, chance=.75, expressions={
                      "pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": [[0, 20], [2, 80]]}})]
    bridge.calls.clear()
    source.copy_clip(1, 16, destination=destination)
    source.split_clip(0, 2)
    source.replace_notes(0, notes)
    source.replace_notes(0, [])
    assert bridge.calls == [
        ("clip.copy", {"path": "Group/MIDI", "clip": 1, "start": 16., "destination": {"path": "Group/Copy"}}),
        ("clip.split", {"path": "Group/MIDI", "clip": 0, "beat": 2.}),
        ("clip.replace_notes", {"path": "Group/MIDI", "clip": 0, "notes": notes}),
        ("clip.replace_notes", {"path": "Group/MIDI", "clip": 0, "notes": []}),
    ]


@pytest.mark.parametrize("note", [{}, {"key": 128, "duration": 1}, {"key": True, "duration": 1},
    {"key": 60, "duration": 0}, {"key": 60, "duration": 1, "channel": 1.5},
    {"key": 60, "duration": 1, "start": -1}, {"key": 60, "duration": 1, "chance": 2},
    {"key": 60, "duration": 1, "on_velocity": float("nan")},
    {"key": 60, "duration": 1, "muted": 1}, {"key": 60, "duration": 1, "unknown": 0},
    {"key": 60, "duration": 1, "expressions": {"unknown": {}}},
    {"key": 60, "duration": 1, "expressions": {"pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": []}}},
    {"key": 60, "duration": 1, "expressions": {"pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": [[1, .2], [0, .3]]}}},
    {"key": 60, "duration": 1, "expressions": {"pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": [[2, .2]]}}},
])
def test_note_replacement_validates_all_notes_before_rpc(bridge, note):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.replace_notes(0, [{"key": 60, "duration": 1}, note])
    assert bridge.calls == []


def test_copy_split_reject_invalid_arguments_without_rpc(bridge):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    bridge.calls.clear()
    for value in (-1, True, float("inf")):
        with pytest.raises(ValueError):
            track.copy_clip(0, value)
        with pytest.raises(ValueError):
            track.split_clip(0, value)
    with pytest.raises(ValueError):
        track.copy_clip(0, 4, destination="MIDI")
    assert bridge.calls == []


def test_audio_event_wire_contract(bridge, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Group/Audio")
    source = tmp_path / "tone.wav"
    source.write_bytes(b"fixture")
    bridge.calls.clear()
    track.import_audio_event(2, source, start=4, duration=3, source_offset=.5, gain_db=-6)
    changes = dict(start=8, duration=2, source_offset=.25, gain_db=-3,
                   muted=True, fade_in=.2, fade_out=.3)
    track.update_audio_event(2, 1, **changes)
    track.update_audio_event(2, 0, reverse=True)
    track.delete_audio_event(2, 1)
    assert bridge.calls == [
        ("audio_event.import", {"path": "Group/Audio", "clip": 2,
                                "file": str(source.resolve()), "start": 4,
                                "duration": 3, "source_offset": .5, "gain_db": -6}),
        ("audio_event.update", {"path": "Group/Audio", "clip": 2, "event": 1, "changes": changes}),
        ("audio_event.update", {"path": "Group/Audio", "clip": 2, "event": 0, "changes": {"reverse": True}}),
        ("audio_event.delete", {"path": "Group/Audio", "clip": 2, "event": 1}),
    ]


@pytest.mark.parametrize("changes", [{}, dict(unknown=1), dict(start=-1), dict(duration=0),
    dict(gain_db=float("nan")), dict(muted=1), dict(reverse="yes"),
    dict(source_offset=-.5), dict(fade_out=-1), dict(source_offset=0, reverse=True)])
def test_invalid_audio_event_changes_rejected_without_rpc(bridge, changes):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_audio_event(0, 0, **changes)
    assert bridge.calls == []


@pytest.mark.parametrize("clip,index", [(-1, 0), (0, -1), (True, 0), (0, 1.5)])
def test_invalid_audio_event_indexes_rejected_without_rpc(bridge, clip, index):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_audio_event(clip, index, muted=True)
    with pytest.raises(ValueError):
        track.delete_audio_event(clip, index)
    assert bridge.calls == []


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


def test_native_project_save_and_document_selection(bridge):
    from pathlib import Path
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    song.save_project("new/song.bwproject")
    song.select_project("new/song.bwproject")
    song.project_info()
    assert bridge.calls == [
        ("project.save_copy", {"path": str(Path("new/song.bwproject").resolve())}),
        ("document.select", {"path": str(Path("new/song.bwproject").resolve())}),
        ("document.info", {}),
    ]


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


def test_duplicate_arranger_range(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    assert song.duplicate_range(304, 336) == {"start": 304.0, "end": 336.0}
    assert bridge.calls == [("project.duplicate_range", {"start": 304.0, "end": 336.0})]
    for start, end in [(-1, 4), (4, 4), (8, 4), (0, float("inf")), (float("nan"), 4)]:
        with pytest.raises(ValueError):
            song.duplicate_range(start, end)
    assert len(bridge.calls) == 1


def test_empty_time_insertion_and_delete_range(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    song.insert_time(8, 4)
    song.delete_range(8, 12)
    assert bridge.calls == [
        ("project.insert_time", {"start": 8.0, "end": 12.0}),
        ("project.delete_range", {"start": 8.0, "end": 12.0}),
    ]
    for start, length in [(-1, 4), (0, 0), (4, -2), (0, float("nan")), (0, float("inf")), (1e308, 1e308)]:
        with pytest.raises(ValueError):
            song.insert_time(start, length)
    for start, end in [(4, 4), (8, 4), (-1, 0), (0, float("inf"))]:
        with pytest.raises(ValueError):
            song.delete_range(start, end)
    assert len(bridge.calls) == 2
