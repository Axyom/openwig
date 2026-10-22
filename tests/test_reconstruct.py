"""Reconstruction wire contract against a fake Java bridge (no Bitwig needed)."""

from openwig.java_song import JavaSong
from openwig.reconstruct import Reconstructor, _enum_index, _modulator_user_name, _rgba


class RebuildBridge:
    """Answers the calls a rebuild makes and records them in order."""

    def __init__(self):
        self.calls = []
        self.main = 0
        self.fx = 0
        self.devices = {}
        self.clips = {}
        self.modulators = {}

    def start(self):
        pass

    def stop(self):
        pass

    def wait_connected(self, timeout):
        return True

    def request(self, method, params=None, timeout=None):
        params = params or {}
        self.calls.append((method, params))
        key = (params.get("path"), str(params.get("chain")))
        if method == "host.version":
            return {"version": "6.1.5"}
        if method == "document.status":
            return {"active": True}
        if method == "transport.set_tempo":
            return {"bpm": params["bpm"]}
        if method == "fxtrack.count":
            return {"count": self.fx}
        if method == "fxtrack.create":
            self.fx += 1
            return {"count": self.fx}
        if method == "track.create_group":
            path = f"{params['parent']}/{params['name']}" if params.get("parent") else params["name"]
            self.main += 0 if params.get("parent") else 1
            return {"count": self.main, "index": self.main - 1, "name": params["name"], "path": path}
        if method == "track.count":
            return {"count": self.main}
        if method == "track.create":
            if params.get("parent"):
                return {"count": self.main, "path": f"{params['parent']}/{params['name']}"}
            self.main += 1
            return {"count": self.main}
        if method == "track.snapshot":
            return {"name": "?"}
        if method == "device.count":
            return {"count": self.devices.get(key, 0)}
        if method in ("device.insert_factory", "device.insert_plugin"):
            index = self.devices.get(key, 0)
            self.devices[key] = index + 1
            return {"count": index + 1, "device": index}
        if method == "device.list_parameters":
            return [{"path": "GAIN", "kind": "double"}, {"path": "MONO", "kind": "boolean"}]
        if method == "device.structure":
            return {"modulators": self.modulators.get((key, params["device"]), []), "chains": []}
        if method == "device.modulator_add":
            added = self.modulators.setdefault((key, params["device"]), [])
            added.append({"index": len(added), "modulator_id": params["modulator_id"],
                          "x": params.get("x"), "y": params.get("y"), "device_name": "LFO"})
            return {"modulator": len(added) - 1, "count": len(added)}
        if method == "device.model_fields":
            return {"model_fields": [{"descriptor": "sample_player_atom", "component": "CONTENTS/PLAYER",
                                      "fields": {"playback_mode": "repitch", "playhead_freeze": False}}]}
        if method == "clip.create_arranger_with_notes":
            self.clips[key] = self.clips.get(key, 0) + 1
            return {"count": self.clips[key]}
        if method == "scene.snapshot":
            return {"count": 0, "scenes": []}
        return {}


def dump():
    note = {"key": 60, "channel": 0, "start": 0, "duration": 1, "on_velocity": .8, "off_velocity": .8,
            "muted": False, "expressions": []}
    return {
        "transport": {"tempo": 150, "groove_enabled": True, "shuffle": .2,
                      "time_signature": {"numerator": 4, "denominator": 4}},
        "metadata": {},
        "effect_tracks": [{"name": "Verb", "path": "fx/Verb", "devices": [], "sends": [], "volume": 1, "pan": .5}],
        "master": {"devices": [], "automation": []},
        "main_tracks": [{
            "name": "Drums", "path": "Drums", "is_group": True, "track_type": "HYBRID", "source": None,
            "destination": {"path": "master"}, "devices": [], "sends": [], "volume": 1, "pan": .5,
            "children": [{
                "name": "Kick", "path": "Drums/Kick", "is_group": False, "track_type": "INSTRUMENT",
                "destination": {"path": "Drums"}, "volume": .5, "pan": .5, "color": "#fe55b7",
                "sends": [{"target": "Verb", "amount": .25, "mode": "AUTO", "index": 0}],
                "devices": [{
                    "kind": "native", "device_id": "e4815188-ba6f-4d14-bcfc-2dcb8f778ccb", "device_name": "Tool",
                    "display_name": "", "enabled": True, "active": True,
                    "parameters": [{"path": "GAIN", "kind": "double"}, {"path": "MONO", "kind": "boolean"}],
                    "values": {"GAIN": 1.5, "MONO": True},
                    "modulators": [{"index": 0, "modulator_id": "m", "device_name": "LFO", "display_name": "LFO",
                                    "x": 0, "y": 0, "parameters": [
                                        {"path": "RATE", "kind": "double", "value": 0, "normalized": 2.5},
                                        {"path": "BIPOLAR", "kind": "boolean", "value": True}],
                                    "bindings": [{"component": "LFO", "amount": .4, "amount_normalized": .2,
                                                  "target_parameter": "GAIN",
                                                  "target_owner": {"path": "Drums/Kick", "device": 0}}]}],
                    "chains": [], "sidechains": []}],
                "clips": [{"content": "note", "start": 4, "duration": 4, "loop": False, "loop_start": 0,
                           "loop_duration": 4, "notes": [note, dict(note, start=2, key=72)]}],
                "automation": [{"target_path": "On", "target_status": "model", "target_scope": "device",
                                "device": 0, "points": [
                                    {"time": -63072000, "kind": "boolean", "value": True},
                                    {"time": 8, "kind": "boolean", "value": False},
                                    {"time": 2, "kind": "boolean", "value": False},
                                    {"time": 16, "kind": "boolean", "value": True}]}],
                "children": []}],
        }],
        "scenes": [{"index": 0, "name": "Drop"}],
        "cue_markers": [{"time": 32, "name": "DROP"}],
    }


def calls(bridge, method):
    return [params for name, params in bridge.calls if name == method]


def rebuild():
    bridge = RebuildBridge()
    song = JavaSong(bridge=bridge)
    report = Reconstructor(song, dump()).run()
    return bridge, report


def test_helpers():
    assert _rgba("#ff0000") == (1.0, 0.0, 0.0, 1.0)
    assert _rgba("#00000000") is None
    assert _enum_index("CPO[member_name=value,object=Timebase,value=4]") == 4
    assert _enum_index(3) == 3 and _enum_index(True) is None


def test_modulator_user_names():
    # The reader's user_name wins; older dumps tell automatic titles from the title itself.
    assert _modulator_user_name({"device_name": "Macro 3", "display_name": "Macro", "user_name": ""}) == ""
    assert _modulator_user_name({"device_name": "Macro 3", "display_name": "Macro", "user_name": "Macro 3"}) == "Macro 3"
    assert _modulator_user_name({"device_name": "Macro 3", "display_name": "Macro"}) == ""
    assert _modulator_user_name({"device_name": "LFO", "display_name": "LFO"}) == ""
    assert _modulator_user_name({"device_name": "MonoLow", "display_name": "Button"}) == "MonoLow"


def test_rebuild_writes_only_changed_model_fields():
    bridge = RebuildBridge()
    data = dump()
    device = data["main_tracks"][0]["children"][0]["devices"][0]
    device["model_fields"] = [{"descriptor": "sample_player_atom", "component": "CONTENTS/PLAYER",
                               "fields": {"playback_mode": "spectral", "playhead_freeze": False}}]
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    (write,) = calls(bridge, "device.set_model_fields")
    assert write["descriptor"] == "sample_player_atom" and write["index"] == 0
    assert write["fields"] == {"playback_mode": "spectral"}


def test_rebuild_renames_only_user_named_modulators():
    bridge = RebuildBridge()
    data = dump()
    device = data["main_tracks"][0]["children"][0]["devices"][0]
    first = device["modulators"][0]
    device["modulators"] = [dict(first, device_name="LFO 1", bindings=[]),
                            dict(first, index=1, device_name="Wobble", bindings=[])]
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    assert [(p["modulator"], p["name"]) for p in calls(bridge, "device.modulator_rename")
            if "parameter" not in p] == [(1, "Wobble")]


def test_rebuild_writes_structure_devices_and_content():
    bridge, report = rebuild()
    assert report.failed == [], report.failed
    # Native devices by definition UUID; switches before numbers, numbers in the reader's scale.
    assert calls(bridge, "device.insert_factory")[0]["id"] == "e4815188-ba6f-4d14-bcfc-2dcb8f778ccb"
    writes = calls(bridge, "device.set_parameters")
    assert writes[0]["params"] == {"MONO": True}
    assert writes[1]["params"] == {"GAIN": 150.0} and writes[1]["unit"] == "PERCENTAGE_OF_RANGE"
    # Modulators stage values (normalized x 100) and bind with the native amount.
    add = calls(bridge, "device.modulator_add")[0]
    assert add["values"] == {"RATE": 250.0, "BIPOLAR": True} and add["unit"] == "PERCENTAGE_OF_RANGE"
    bind = calls(bridge, "device.modulator_bind")[0]
    assert bind["native_amount"] == .4 and bind["target_parameter"] == "GAIN" and "target_device" not in bind
    # Children of groups are routed explicitly; sends follow their effect track.
    assert {"path": "Drums #rw2/Kick #rw3", "destination": "Drums #rw2"} in calls(bridge, "track.set_destination")
    assert calls(bridge, "track.set_send")[0]["send"] == 0
    # Clip container: loop region first, then notes.
    assert calls(bridge, "clip.create_arranger_with_notes")[0]["start"] == 4.0
    assert len(calls(bridge, "clip.replace_notes")[0]["notes"]) == 2
    assert calls(bridge, "clip.update")[0]["changes"]["loop_duration"] == 4.0
    # Automation drops the negative anchor and the stray out-of-order point.
    lane = calls(bridge, "automation.write_target")[0]
    assert lane["points"] == [[8, False], [16, True]] and lane["kind"] == "boolean"
    assert report.unsupported["automation_point_out_of_order"] == 2
    # Groove, scenes, markers and real titles at the end.
    assert calls(bridge, "transport.set_groove")[0] == {"enabled": True, "shuffle": .2}
    assert calls(bridge, "cue_marker.create")[0]["name"] == "DROP"
    renames = [p["name"] for p in calls(bridge, "track.rename")]
    assert renames == ["Kick", "Verb", "Drums"]


class DefaultsBridge(RebuildBridge):
    """A fresh native device arrives with one default modulator (a Polysynth's LFO)."""

    def request(self, method, params=None, timeout=None):
        params = params or {}
        if method == "device.insert_factory":
            result = super().request(method, params, timeout)
            key = (params.get("path"), str(params.get("chain")))
            self.modulators[(key, result["device"])] = [{"index": 0, "modulator_id": "default", "device_name": "LFO 1"}]
            return result
        if method == "device.modulator_delete":
            self.calls.append((method, params))
            key = (params.get("path"), str(params.get("chain")))
            self.modulators[(key, params["device"])].pop(params["modulator"])
            return {"count": len(self.modulators[(key, params["device"])])}
        return super().request(method, params, timeout)


def test_rebuild_replaces_default_modulators_and_steps_past_the_end_marker():
    bridge = DefaultsBridge()
    data = dump()
    kick = data["main_tracks"][0]["children"][0]
    kick["clips"][0].update(duration=2, loop_start=2, loop_duration=2, play_start_offset=2)
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    # The fresh default goes first, so the dump's modulator lands at its own index.
    names = [name for name, _ in bridge.calls]
    assert names.index("device.modulator_delete") < names.index("device.modulator_add")
    assert calls(bridge, "device.modulator_bind")[0]["modulator"] == 0
    # A play start exactly on the end marker is approached from beyond it.
    offsets = [p["changes"]["play_start_offset"] for p in calls(bridge, "clip.update")
               if "play_start_offset" in p["changes"]]
    assert offsets == [4.0, 2.0]


def test_rebuild_writes_clip_names_loops_and_launch_settings():
    bridge = RebuildBridge()
    data = dump()
    kick = data["main_tracks"][0]["children"][0]
    defaults = {"launch_quantization": "DEFAULT", "launch_mode": "DEFAULT", "next_action": "STOP",
                "next_action_time": 16, "use_loop_start_as_quantization_reference": False}
    kick["clips"][0]["launch_settings"] = dict(defaults, launch_mode="FROM_START")
    kick["clips"].append(dict(kick["clips"][0], start=12, launch_settings=defaults))
    kick["clip_slots"] = [{"index": 1, "has_stop": True, "clip": {
        "content": "note", "duration": 4, "loop": True, "loop_start": 0, "loop_duration": 16, "notes": [],
        "launch_settings": dict(defaults, next_action="PLAY_NEXT", next_action_time=8)}}]
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    updates = [(p.get("clip"), p.get("slot"), p["changes"]) for p in calls(bridge, "clip.update")]
    # Names are always written (an audio import titles the clip after its file).
    assert updates[0] == (0, None, {"loop": False, "loop_start": 0.0, "loop_duration": 4.0, "name": ""})
    # Launch settings: only those differing from a new clip, arranger clips included.
    assert [u for u in updates if u[2].keys() & {"launch_mode", "next_action"}] == [
        (0, None, {"launch_mode": "FROM_START"}),
        (None, 1, {"next_action": "PLAY_NEXT", "next_action_time": 8})]
    # A launcher clip's loop can be longer than the clip it was created as.
    assert (None, 1, {"loop": True, "loop_start": 0.0, "loop_duration": 16.0, "name": ""}) in updates


def test_rebuild_writes_lane_points_then_automation_clips():
    bridge = RebuildBridge()
    data = dump()
    kick = data["main_tracks"][0]["children"][0]
    real = lambda t, v, **extra: dict({"time": t, "kind": "real", "value": v / 10, "raw_value": v,
                                       "interpolation": "linear"}, **extra)
    kick["automation"] = [{
        "target_path": "CONTENTS/GAIN", "target_status": "model", "target_scope": "device", "device": 0,
        # The composite walk interleaves clip-relative points; only base_points and clips are written.
        "points": [real(0, 0), real(1.5, 3), real(8, 0), real(1.5, 3), real(16, 0)],
        "base_points": [real(0, 0), real(8, 0, model_interpolation="hold", curvature=0), real(16, 0)],
        "clips": [{"start": 4, "end": 8, "loop": True, "loop_start": 0, "loop_duration": 2,
                   "play_start_offset": 0, "points": [real(0, 0), real(1.5, 3, curvature=.4)]},
                  {"start": 12, "end": 16, "loop": False, "loop_start": 0, "loop_duration": 4,
                   "play_start_offset": 0, "points": [real(0, 0), real(1.5, 3)]}]}]
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    lane = calls(bridge, "automation.write_target")[0]
    assert lane["points"] == [[0, 0, 0, "linear"], [8, 0, 0, "hold"], [16, 0, 0, "linear"]]
    clips = calls(bridge, "automation.write_clips")[0]
    assert clips["target"] == "CONTENTS/GAIN" and clips["kind"] == "real" and "points" not in clips
    assert clips["clips"][0]["points"] == [[0, 0, 0, "linear"], [1.5, 3, .4, "linear"]]
    assert clips["clips"][0]["loop"] is True and clips["clips"][1]["start"] == 12
    assert report.applied["automation_clip"] == 2
    assert "automation_point_out_of_order" not in report.unsupported


def test_self_bindings_of_a_nested_device_stay_on_that_device():
    # The reader marks a target on the modulator's own device "self"; inside a chain that owner
    # also carries the chain and route, which must not reroute the bind from the track root.
    class ChainHandle:
        _chain_steps = [{"device": 3, "component": "CONTENTS/CHAIN_LIST/CHAIN0"}]

        def _track_params(self, **params):
            return dict(path="Bass/Bass", chain=self._chain_steps, **params)

    bridge = RebuildBridge()
    rebuilder = Reconstructor(JavaSong(bridge=bridge), dump())
    owner = {"path": "Bass/Bass", "chain": 0, "device": 0, "self": True,
             "chain_path": [{"device": 3, "component": "CONTENTS/CHAIN_LIST/CHAIN0"}]}
    handle = ChainHandle()
    rebuilder._pending_bindings = [
        (handle, 0, 1, {"component": "RANDOM", "amount": 1, "target_parameter": "ROOT_GENERIC_MODULE/PID5",
                        "target_owner": owner}, "here", [], {0: 0}),
        (handle, 0, 1, {"amount": .5, "target_parameter": "MODULATORS/2/CONTENTS/VALUE",
                        "target_owner": owner}, "here", [], {2: 0}),
    ]
    rebuilder.bindings()
    sent = calls(bridge, "device.modulator_bind")
    assert [p["target_parameter"] for p in sent] == ["ROOT_GENERIC_MODULE/PID5", "MODULATORS/0/CONTENTS/VALUE"]
    assert all("target_chain" not in p and "target_device" not in p and "target_component" not in p for p in sent)
    assert sent[0]["source"] == "RANDOM" and sent[0]["native_amount"] == 1


def test_rebuild_deactivates_tracks_and_devices_after_mappings_and_automation():
    # A deactivated track or device unloads its plugins, and with them the parameters
    # that mappings and automation target.
    bridge = RebuildBridge()
    data = dump()
    kick = data["main_tracks"][0]["children"][0]
    kick["is_active"] = False
    kick["devices"][0]["active"] = False
    report = Reconstructor(JavaSong(bridge=bridge), data).run()
    assert report.failed == [], report.failed
    order = [name for name, _ in bridge.calls]
    last_edit = max(order.index("device.modulator_bind"), len(order) - 1 - order[::-1].index("automation.write_target"))
    assert order.index("track.set_active") > last_edit
    assert order.index("device.set_active") > last_edit
