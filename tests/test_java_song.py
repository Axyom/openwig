"""JavaSong wire-contract tests against a fake bridge (no Bitwig needed)."""

from pathlib import Path

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
        if method in {"document.open", "document.close"}:
            return {"operation": method.split(".")[1], "state": "running", "job": method, "path": params["path"]}
        if method == "document.job_status":
            return {"state": getattr(self, "job_state", "complete"), "job": params["job"], "error": "boom"}
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
        if method == "scene.snapshot":
            return {"count": 1, "scenes": [{"index": 0, "name": "A", "comment": "", "color": None}]}
        if method in {"scene.insert", "scene.delete", "scene.move"}:
            return params
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
        if method in {"clip.update", "clip.delete", "clip.import_audio", "clip.copy", "clip.split", "clip.replace_notes", "audio_event.import", "audio_event.update", "audio_event.delete", "note.import", "note.update", "note.delete"}:
            return params
        if method in {"launcher.read", "launcher.settings", "launcher.launch", "launcher.stop", "launcher.return_to_arranger",
                      "launcher.put_notes", "launcher.import_audio", "launcher.copy_arranger", "launcher.clear"}:
            return params
        if method in {"device.insert_plugin", "device.plugin_parameters"}:
            return params
        if method in {"device.export_plugin_state", "device.capture_plugin_state", "device.plugin_state_status"}:
            return {"state": "complete", "job": "fixture", **params}
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
    {"key": 60, "duration": 1, "expressions": {"pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": [[1, .2], [0, .3]]}}},
    {"key": 60, "duration": 1, "expressions": {"pressure": {"unit": "PERCENTAGE_OF_RANGE", "points": [[-1, .2]]}}},
])
def test_note_replacement_validates_all_notes_before_rpc(bridge, note):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.replace_notes(0, [{"key": 60, "duration": 1}, note])
    assert bridge.calls == []


def test_individual_note_edits_and_expression_shapes(bridge):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    curve = {"gain": {"unit": "DECIBEL", "points": [[0, -6, .3], [2, 0, 0, "hold"]]}}
    changes = dict(velocity_spread=.2, content_start=.25, chance=.4, chance_enabled=False,
                   occurrence="FILL", occurrence_enabled=True, recurrence=42, recurrence_enabled=True,
                   repeat_count=-3, repeat_curve=.2, repeat_enabled=True, expressions=curve)
    bridge.calls.clear()
    track.update_note(0, 1, **changes)
    track.import_note(slot=2, data={"key": 60, "duration": 2})
    track.delete_note(slot=2, index=3)
    assert bridge.calls == [
        ("note.update", {"path": "MIDI", "clip": 0, "note": 1, "changes": changes}),
        ("note.import", {"path": "MIDI", "slot": 2, "data": {"key": 60, "duration": 2}}),
        ("note.delete", {"path": "MIDI", "slot": 2, "note": 3}),
    ]


@pytest.mark.parametrize("changes", [
    {}, {"key": 128}, {"velocity_spread": -1}, {"content_start": -1},
    {"chance_enabled": 1}, {"occurrence": "UNKNOWN"}, {"recurrence": 2048},
    {"repeat_count": -128}, {"repeat_curve": float("nan")},
    {"expressions": {"gain": {"unit": "RATIO", "points": [[0, 1, 2]]}}},
    {"expressions": {"gain": {"unit": "RATIO", "points": [[0, 1, 0, "bezier"]]}}},
    {"expressions": {"formant": {"unit": "SEMITONES", "points": [[0, 1]]}}},
])
def test_note_update_validation_before_rpc(bridge, changes):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_note(0, **changes)
    assert bridge.calls == []


def test_expression_clear_and_trimmed_content_coordinates(bridge):
    track = JavaSong(bridge=bridge).track_at("MIDI")
    # Curves may extend past a trimmed note's visible duration.
    curves = {"gain": {"unit": "RATIO", "points": []},
              "pressure": {"unit": "RATIO", "points": [[0, 0], [8, 1]]}}
    track.replace_notes(0, [{"key": 60, "duration": 1, "content_start": 2, "expressions": curves}])
    assert bridge.calls[-1][1]["notes"][0]["expressions"] == curves


def test_audio_expression_and_probability_wire_contract(bridge):
    track = JavaSong(bridge=bridge).track_at("Audio")
    changes = dict(expressions={"formant": {"unit": "SEMITONES", "points": [[0, 2], [2, -1]]}},
                   chance_enabled=True, chance=.7, recurrence=42, repeat_enabled=False)
    bridge.calls.clear()
    track.update_audio_event(slot=1, **changes)
    assert bridge.calls == [("audio_event.update", {"path": "Audio", "slot": 1, "event": 0, "changes": changes})]


def test_vst3_state_parameter_contract(bridge, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Plugin")
    plugin_id = "74F3503C00B24D4E000A6C3100D57595"
    state = tmp_path / "source.vstpreset"
    state.write_bytes(b"VST3" + b"\x01\x00\x00\x00" + plugin_id.encode("ascii") + bytes(8))
    output = tmp_path / "output.vstpreset"
    bridge.calls.clear()
    track.insert_plugin("kHs Haas", plugin_id.lower(), state=state)
    track.set_plugin_parameters(0, {1: 0, "2": .2})
    track.export_plugin_state(0, output)
    assert bridge.calls == [
        ("device.insert_plugin", {"path": "Plugin", "name": "kHs Haas", "format": "vst3", "id": plugin_id, "state": str(state.resolve())}),
        ("device.plugin_parameters", {"path": "Plugin", "device": 0, "values": {"1": 0., "2": .2}}),
        ("device.capture_plugin_state", {"path": "Plugin", "device": 0}),
        ("device.export_plugin_state", {"path": "Plugin", "device": 0, "file": str(output.resolve())}),
    ]


@pytest.mark.parametrize("values", [{}, {True: .5}, {"bad": .5}, {2**32: .5},
    {1: float("nan")}, {1: 1.1}, {1: True}, {1: .3, "1": .4}])
def test_plugin_parameters_validate_before_rpc(bridge, values):
    track = JavaSong(bridge=bridge).track_at("Plugin")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.set_plugin_parameters(0, values)
    assert bridge.calls == []


def test_plugin_state_header_and_existing_export_rejected_locally(bridge, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Plugin")
    file = tmp_path / "existing.vstpreset"; file.write_bytes(b"invalid state")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.insert_plugin("Haas", "74F3503C00B24D4E000A6C3100D57595", state=file)
    with pytest.raises(ValueError):
        track.export_plugin_state(0, file)
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


def test_warp_processing_wire_contract(bridge):
    track = JavaSong(bridge=bridge).track_at("Audio")
    changes = dict(warp_mode="ELASTIQUE_PRO", warp_markers=[[0, 0], [5, 1], [7.5, 3]],
                   content_offset=0, transpose=7, formant=2, pan=-.5,
                   preserve_formants=False, preview_onsets=True,
                   preserve_transients_mode="HARD", transient_rate="QUARTER",
                   warp_grain_size="LARGE", warp_tail_mode="GRAIN")
    bridge.calls.clear()
    track.update_audio_event(0, 1, **changes)
    assert bridge.calls == [("audio_event.update", {"path": "Audio", "clip": 0, "event": 1, "changes": changes})]


@pytest.mark.parametrize("changes", [dict(warp_mode="bad"), dict(warp_mode=1),
    dict(warp_markers=[]), dict(warp_markers=[[0, 0]]),
    dict(warp_markers=[[0, 0], [0, 1]]), dict(warp_markers=[[0, 1], [2, .5]]),
    dict(warp_markers=[[0, 0], [2, float("nan")]]), dict(warp_markers=[[0, 0, 1], [2, 3]]),
    dict(transpose=100), dict(formant=float("inf")), dict(pan=2),
    dict(preserve_formants=1), dict(preview_onsets="yes"), dict(content_offset=-1),
    dict(transient_rate="EIGHTH"), dict(warp_grain_size="huge"), dict(warp_tail_mode="BAD")])
def test_invalid_warp_changes_rejected_without_rpc(bridge, changes):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_audio_event(0, 0, **changes)
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


def test_open_project_waits_for_the_native_job_and_selects(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    song.open_project("copies/song.bwproject")
    path = str(Path("copies/song.bwproject").resolve())
    assert bridge.calls == [
        ("document.open", {"path": path, "activate_engine": False}),
        ("document.job_status", {"job": "document.open"}),
        ("document.select", {"path": path}),
    ]


def test_close_project_requires_explicit_discard(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    with pytest.raises(ValueError):
        song.close_project("copies/song.bwproject")
    assert bridge.calls == []
    song.close_project("copies/song.bwproject", discard_changes=True)
    path = str(Path("copies/song.bwproject").resolve())
    assert bridge.calls == [
        ("document.close", {"path": path, "discard_changes": True}),
        ("document.job_status", {"job": "document.close"}),
    ]


def test_failed_document_job_raises(bridge):
    from openwig.bridge import BridgeError
    song = JavaSong(bridge=bridge)
    bridge.job_state = "failed"
    with pytest.raises(BridgeError, match="boom"):
        song.open_project("copies/song.bwproject")


def test_scene_bank_structure_requests(bridge):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    assert song.scenes()[0]["name"] == "A"
    song.insert_scene()
    song.insert_scene(0)
    song.move_scene(2, 0)
    song.delete_scene(1)
    assert bridge.calls == [
        ("scene.snapshot", {}),
        ("scene.insert", {}),
        ("scene.insert", {"index": 0}),
        ("scene.move", {"index": 2, "destination": 0}),
        ("scene.delete", {"index": 1}),
    ]


@pytest.mark.parametrize("call", [
    lambda s: s.insert_scene(-1), lambda s: s.delete_scene(True),
    lambda s: s.move_scene(0, 1.0), lambda s: s.update_scene(-2, name="x"),
])
def test_scene_indices_are_validated_before_sending(bridge, call):
    song = JavaSong(bridge=bridge)
    bridge.calls.clear()
    with pytest.raises(ValueError):
        call(song)
    assert bridge.calls == []


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


def test_launcher_wire_contract(bridge, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Launcher/MIDI")
    notes = [dict(key=60, duration=2)]
    source = tmp_path / "tone.wav"
    source.write_bytes(b"fixture")
    bridge.calls.clear()
    track.read_launcher_slot(3)
    track.put_launcher_notes(2, notes, 4, name="Loop", has_stop=False)
    track.put_launcher_notes(2, notes, 4, replace=True)
    track.import_launcher_audio(1, source, gain_db=-3)
    track.copy_arranger_to_launcher(0, clip=1)
    track.clear_launcher_slot(2, has_stop=False)
    track.update_launcher_clip(2, duration=8, muted=True)
    track.delete_launcher_clip(2)
    track.update_launch_settings(2, launch_quantization="BAR", next_action="PLAY_NEXT")
    track.launch_slot(2)
    track.stop_launcher(immediate=True)
    track.return_to_arranger()
    track.import_audio_event(None, source, slot=1)
    track.update_audio_event(None, 0, slot=1, muted=True)
    track.delete_audio_event(None, 0, slot=1)
    assert bridge.calls == [
        ("launcher.read", {"path": "Launcher/MIDI", "slot": 3}),
        ("launcher.put_notes", {"path": "Launcher/MIDI", "slot": 2, "notes": notes,
                                "duration": 4.0, "loop": True, "has_stop": False, "name": "Loop"}),
        ("launcher.put_notes", {"path": "Launcher/MIDI", "slot": 2, "notes": notes,
                                "duration": 4.0, "loop": True, "replace": True}),
        ("launcher.import_audio", {"path": "Launcher/MIDI", "slot": 1, "file": str(source.resolve()),
                                   "start": 0, "source_offset": 0, "gain_db": -3, "loop": True}),
        ("launcher.copy_arranger", {"path": "Launcher/MIDI", "slot": 0, "clip": 1}),
        ("launcher.clear", {"path": "Launcher/MIDI", "slot": 2, "has_stop": False}),
        ("clip.update", {"path": "Launcher/MIDI", "slot": 2, "changes": {"duration": 8, "muted": True}}),
        ("clip.delete", {"path": "Launcher/MIDI", "slot": 2}),
        ("launcher.settings", {"path": "Launcher/MIDI", "slot": 2,
                               "changes": {"launch_quantization": "BAR", "next_action": "PLAY_NEXT"}}),
        ("launcher.launch", {"path": "Launcher/MIDI", "slot": 2}),
        ("launcher.stop", {"path": "Launcher/MIDI", "immediate": True}),
        ("launcher.return_to_arranger", {"path": "Launcher/MIDI"}),
        ("audio_event.import", {"path": "Launcher/MIDI", "slot": 1, "file": str(source.resolve()),
                                "start": 0, "source_offset": 0, "gain_db": 0}),
        ("audio_event.update", {"path": "Launcher/MIDI", "slot": 1, "event": 0, "changes": {"muted": True}}),
        ("audio_event.delete", {"path": "Launcher/MIDI", "slot": 1, "event": 0}),
    ]


@pytest.mark.parametrize("call", [
    lambda t: t.read_launcher_slot(-1),
    lambda t: t.put_launcher_notes(0, [{"key": 60, "duration": 1}], 0),
    lambda t: t.put_launcher_notes(0, [{"key": 60}], 4),
    lambda t: t.put_launcher_notes(0, [{"key": 60, "duration": 1}], 4, loop="yes"),
    lambda t: t.put_launcher_notes(0, [{"key": 60, "duration": 1}], 4, replace="yes"),
    lambda t: t.import_launcher_audio(0, "missing.wav"),
    lambda t: t.copy_arranger_to_launcher(0, -2),
    lambda t: t.clear_launcher_slot(0, has_stop=1),
    lambda t: t.update_launcher_clip(0, start=4),
    lambda t: t.update_launch_settings(0, launch_quantization="NOPE"),
    lambda t: t.update_launch_settings(0, launch_mode="NOPE"),
    lambda t: t.update_launch_settings(0, next_action="NOPE"),
    lambda t: t.update_launch_settings(0, next_action_time=-1),
    lambda t: t.update_launch_settings(0, has_stop="yes"),
    lambda t: t.update_launch_settings(0, unknown=1),
    lambda t: t.launch_slot(True),
    lambda t: t.stop_launcher(immediate=1),
])
def test_invalid_launcher_calls_rejected_without_rpc(bridge, call):
    track = JavaSong(bridge=bridge).track_at("Launcher/MIDI")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        call(track)
    assert bridge.calls == []


@pytest.mark.parametrize("clip,slot", [(0, 0), (None, None)])
def test_audio_events_reject_ambiguous_addressing(bridge, clip, slot, tmp_path):
    track = JavaSong(bridge=bridge).track_at("Audio")
    source = tmp_path / "tone.wav"
    source.write_bytes(b"fixture")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.import_audio_event(clip, source, slot=slot)
    with pytest.raises(ValueError):
        track.update_audio_event(clip, 0, slot=slot, muted=True)
    with pytest.raises(ValueError):
        track.delete_audio_event(clip, 0, slot=slot)
    assert bridge.calls == []


def test_warped_reverse_offset_and_resolution_wire_contract(bridge):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    track.update_audio_event(0, 0, reverse=True)
    track.update_audio_event(0, 0, source_offset=2.5, formant_envelope_resolution=256)
    assert bridge.calls == [
        ("audio_event.update", {"path": "Audio", "clip": 0, "event": 0, "changes": {"reverse": True}}),
        ("audio_event.update", {"path": "Audio", "clip": 0, "event": 0,
                                "changes": {"source_offset": 2.5, "formant_envelope_resolution": 256}}),
    ]


@pytest.mark.parametrize("changes", [
    dict(formant_envelope_resolution=True), dict(formant_envelope_resolution=7),
    dict(formant_envelope_resolution=513), dict(formant_envelope_resolution=128.5),
    dict(source_offset=1, content_offset=1), dict(source_offset=1, reverse=False),
])
def test_audio_coordinate_ambiguity_and_resolution_rejected_before_rpc(bridge, changes):
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.update_audio_event(0, 0, **changes)
    assert bridge.calls == []
