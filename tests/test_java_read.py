from copy import deepcopy
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

import pytest

from openwig.java_read import export_to_dict, parity_report, _xml_node


FIXTURE = Path(__file__).parent / "fixtures" / "dawproject-sample"


class Exporter:
    def export_dawproject(self, path):
        self.path = Path(path)
        with ZipFile(path, "w") as archive:
            for name in ("project.xml", "metadata.xml"):
                archive.write(FIXTURE / name, name)


def sample():
    return {"project": _xml_node(ET.parse(FIXTURE / "project.xml").getroot()),
            "metadata": _xml_node(ET.parse(FIXTURE / "metadata.xml").getroot())}


def native():
    return {"main_tracks": [{"name": "Group", "is_group": True, "clips": [], "devices": [],
                             "children": [
        {"name": "Synth", "clips": [{"start": 0, "duration": 4, "notes": [
            {"channel": 0, "key": 60, "start": 0, "duration": 1,
             "on_velocity": 0.8, "off_velocity": 0.5}]}],
         "devices": [{"kind": "plugin", "enabled": True, "active": True, "display_name": ""}]},
        {"name": "Audio", "clips": [{"start": 0, "duration": 4}], "devices": []}]}],
            "master": {"name": "Master", "clips": [], "devices": []},
            "effect_tracks": [], "cue_markers": [{"time": 0, "name": "Start", "color": "#ff0000"}],
            "transport": {"tempo": 120, "time_signature": {"numerator": 4, "denominator": 4}},
            "metadata": {"title": "Synthetic parity fixture", "artist": "Example"}}


def test_export_preserves_ids_lists_attributes_text_and_cleans_temp():
    song = Exporter()
    parsed = export_to_dict(song)
    assert not song.path.parent.exists()
    root = parsed["project"]
    assert root["attributes"]["version"] == "1.0"
    tracks = root["Structure"][0]["Track"]
    assert len(tracks) == 2
    assert tracks[0]["attributes"]["id"] == "group"
    assert parsed["metadata"]["Title"][0]["text"] == "Synthetic parity fixture"


def test_live_generated_fixture_contains_only_authored_synthetic_structure(tmp_path):
    from openwig.java_read import _children, _walk

    class GeneratedExporter:
        def export_dawproject(self, path):
            with ZipFile(path, "w") as archive:
                for name in ("project.xml", "metadata.xml"):
                    archive.write(FIXTURE / "generated" / name, name)

    daw = export_to_dict(GeneratedExporter(), tmp_path / "generated.dawproject")
    root = daw["project"]
    assert [t["attributes"]["name"] for t in _walk(root, "Track")] == [
        "Fixture Synth", "Fixture Audio", "Fixture FX", "Master"]
    assert {n["attributes"]["key"] for n in _walk(root, "Note")} == {"60", "67"}
    assert len(list(_walk(root, "Note"))) == 2
    devices = [d for ds in _walk(root, "Devices") for d in _children(ds)]
    assert len(devices) == 1 and devices[0]["attributes"]["deviceName"] == "FM-4"
    assert not list(_walk(root, "State")) and not list(_walk(root, "File"))
    synth_send = next(_walk(root, "Send"))
    assert synth_send["attributes"]["type"] == "post"
    assert next(_children(synth_send, "Volume"))["attributes"]["value"] == "0.300000"


def test_explicit_export_is_retained(tmp_path):
    path = tmp_path / "retained.dawproject"
    export_to_dict(Exporter(), path)
    assert path.exists()


def test_failed_export_cleans_temporary_directory():
    class Broken:
        def export_dawproject(self, path):
            self.path = Path(path)
            self.path.write_text("not a zip")
            raise RuntimeError("failed")
    song = Broken()
    with pytest.raises(RuntimeError, match="failed"):
        export_to_dict(song)
    assert not song.path.parent.exists()


def test_namespace_unknown_fields_and_no_metadata(tmp_path):
    class Namespaced:
        def export_dawproject(self, path):
            with ZipFile(path, "w") as archive:
                archive.writestr("project.xml", '<Project xmlns="urn:test"><Future id="x" answer="42"/></Project>')
    parsed = export_to_dict(Namespaced(), tmp_path / "namespace.dawproject")
    assert parsed["metadata"] is None
    assert parsed["project"]["{urn:test}Future"][0]["attributes"] == {"id": "x", "answer": "42"}


def test_scopes_do_not_compare_nested_or_launcher_clips_with_arranger():
    report = parity_report(native(), sample())
    assert report["structure_matches"]
    assert report["sections"]["clips"]["counts"]["arranger_roots"] == {
        "native": 2, "export": 2, "comparable": True, "match": True}
    assert report["sections"]["notes"]["counts"]["arranger_direct"]["export"] == 1
    assert report["diagnostics"]["xml_total_clips"] == 4
    assert report["diagnostics"]["xml_arranger_nested_clips"] == 1
    assert report["diagnostics"]["xml_launcher_clips"] == 1
    assert report["diagnostics"]["xml_launcher_notes"] == 1
    assert report["diagnostics"]["xml_warp_markers"] == 2
    assert report["diagnostics"]["xml_automation_points"] == 2
    assert not report["native_parity"]


def test_missing_collections_are_depth_gaps_but_present_wrong_counts_are_errors():
    dump = native()
    report = parity_report(dump, sample())
    scenes = report["sections"]["scenes/launcher"]["counts"]["scenes"]
    assert scenes == {"native": None, "export": 1, "comparable": False, "match": None}
    assert report["depth_gaps"]
    dump["scenes"] = []
    report = parity_report(dump, sample())
    assert {"section": "scenes/launcher", "count": "scenes", "native": 0, "export": 1} in report["structure_mismatches"]


def test_compensating_clip_counts_do_not_hide_reader_bug():
    dump = native()
    a, b = dump["main_tracks"][0]["children"]
    b["clips"].append(a["clips"].pop())
    report = parity_report(dump, sample())
    assert report["sections"]["clips"]["counts"]["arranger_roots"]["match"]
    assert not report["structure_matches"]
    assert {m["count"] for m in report["structure_mismatches"]} == {"track:Group/Synth", "track:Group/Audio"}


def test_track_paths_detect_equal_count_replacement_and_inputs_unchanged():
    dump, daw = native(), sample()
    original = deepcopy((dump, daw))
    dump["main_tracks"][0]["children"][0]["name"] = "Wrong"
    modified = deepcopy(dump)
    report = parity_report(dump, daw)
    assert not report["structure_matches"]
    assert report["structure_mismatches"][0]["missing"] == ["Group/Synth"]
    assert dump == modified and daw == original[1]


def test_coverage_is_based_on_actual_entities_and_includes_schema_fields():
    dump = native()
    report = parity_report(dump, sample())
    assert report["sections"]["devices"]["fields"]["enabled"]["coverage"] == "native"
    assert report["sections"]["devices"]["fields"]["name"]["coverage"] == "export-only"
    assert "devices.deviceVendor" in report["missing_native_fields"]
    assert "devices.pluginVersion" in report["missing_native_fields"]
    assert "clips.warp_markers" in report["missing_native_fields"]
    dump["main_tracks"][0]["children"][0]["devices"].append({"display_name": "Named"})
    report = parity_report(dump, sample())
    assert report["sections"]["devices"]["fields"]["name"]["coverage"] == "partial"


def test_referenced_note_content_counts_each_arranger_instance():
    daw, dump = sample(), native()
    lanes = daw["project"]["Arrangement"][0]["Lanes"][0]["Lanes"][0]
    clip = lanes["Clips"][0]["Clip"][0]
    notes = clip.pop("Notes")
    daw["project"]["SharedContent"] = [{"tag": "SharedContent", "attributes": {}, "Notes": notes}]
    clip["attributes"]["reference"] = "notes"
    lanes["Clips"][0]["Clip"].append(deepcopy(clip))
    clips = dump["main_tracks"][0]["children"][0]["clips"]
    clips.append(deepcopy(clips[0]))
    report = parity_report(dump, daw)
    assert report["structure_matches"]
    assert report["sections"]["notes"]["counts"]["arranger_direct"]["export"] == 2


def test_present_launcher_slots_with_null_clips_have_native_coverage():
    dump = native()
    dump["scenes"] = [{"name": "Scene 1", "color": "#ff0000"}]
    for t in [dump["main_tracks"][0], *dump["main_tracks"][0]["children"], dump["master"]]:
        t["clip_slots"] = []
    dump["main_tracks"][0]["children"][0]["clip_slots"] = [
        {"has_stop": True, "clip": None}, {"has_stop": False, "clip": None}]
    report = parity_report(dump, sample())
    assert report["sections"]["scenes/launcher"]["fields"]["clip"]["coverage"] == "native"
    assert not report["structure_matches"]  # exported slot contains one note clip
    assert report["sections"]["scenes/launcher"]["counts"]["clips"]["match"] is False


def deep_sample():
    """Extend synthetic XML with note/audio envelopes in their real scopes."""
    root = ET.parse(FIXTURE / "project.xml").getroot()
    note = root.find("./Arrangement/Lanes/Lanes/Clips/Clip/Notes/Note")
    note.append(ET.fromstring('<Points unit="semitones"><Target expression="transpose"/>'
                             '<RealPoint time="0" value="0"/><RealPoint time="1" value="2"/></Points>'))
    # The envelope and Warps are siblings; expression is not inside Audio.
    audio_lanes = root.find("./Arrangement/Lanes/Lanes[@track='audio']/Clips/Clip/Lanes/Clips/Clip/Warps")
    parent = next(p for p in root.iter() if audio_lanes in list(p))
    lanes = ET.Element("Lanes")
    parent.remove(audio_lanes)
    lanes.append(audio_lanes)
    lanes.append(ET.fromstring('<Points unit="linear"><Target expression="gain"/>'
                              '<RealPoint time="0" value="0.8"/></Points>'))
    parent.append(lanes)
    return {"project": _xml_node(root), "metadata": None}


def deep_native():
    dump = native()
    tracks = [dump["main_tracks"][0], *dump["main_tracks"][0]["children"], dump["master"]]
    for track in tracks:
        track.update(automation=[], source=None, destination=None, color=None)
    synth, audio = tracks[1:3]
    synth["automation"] = [{"target_path": "cutoff", "target_name": "Cutoff",
                            "unit": "normalized", "time_unit": "beats", "points": [
        {"time": 0, "value": 0.2, "kind": "real", "interpolation": "linear"},
        {"time": 4, "value": 0.8, "kind": "real", "interpolation": "linear"}]}]
    synth["devices"][0]["sidechain"] = None
    synth["clips"][0]["notes"][0]["expressions"] = [
        {"expression": "transpose", "unit": "semitones", "points": [
            {"time": 0, "value": 0}, {"time": 1, "value": 2}]}]
    audio["clips"][0].update(content="audio", fade_in=0, fade_out=0, audio_events=[{
        "file": "audio/synthetic.wav", "sample_rate": 48000, "channels": 2,
        "algorithm": "stretch", "warp_markers": [{"time": 0, "content_time": 0},
                                                  {"time": 2, "content_time": 1}],
        "expressions": [{"expression": "gain", "unit": "linear", "points": [
            {"time": 0, "value": 0.8}]}]}])
    return dump


def test_arranger_automation_excludes_audio_and_note_expressions():
    report = parity_report(deep_native(), deep_sample())
    counts = report["sections"]["automation"]["counts"]
    for name, expected in [("lanes", 1), ("points", 2),
                           ("note_expressions_lanes", 1), ("note_expressions_points", 2),
                           ("audio_expressions_lanes", 1), ("audio_expressions_points", 1)]:
        assert counts[name] == {"native": expected, "export": expected, "comparable": True, "match": True}
    assert report["structure_matches"]
    fields = report["sections"]["automation"]["fields"]
    assert fields["time"]["coverage"] == "native"
    assert fields["interpolation"]["coverage"] == "partial"  # expression test points omit it
    assert fields["target"]["coverage"] == "native"
    assert report["diagnostics"]["xml_automation_points"] == 5


def test_audio_events_and_warps_use_event_fields_and_detect_missing_event():
    dump, daw = deep_native(), deep_sample()
    report = parity_report(dump, daw)
    clips = report["sections"]["clips"]
    assert clips["counts"]["audio_events"]["match"]
    assert clips["counts"]["warp_markers"]["match"]
    for name in ["sample_file", "sampleRate", "channels", "algorithm", "warp_markers"]:
        assert clips["fields"][name]["coverage"] == "native"
    dump["main_tracks"][0]["children"][1]["clips"][0]["audio_events"] = []
    report = parity_report(dump, daw)
    assert not report["structure_matches"]
    assert report["sections"]["clips"]["counts"]["audio_events"]["match"] is False


def test_unavailable_plugin_parameter_cache_is_partial_even_with_empty_list():
    dump = deep_native()
    device = dump["main_tracks"][0]["children"][0]["devices"][0]
    device.update(parameters=[], parameters_status="unavailable_model_cache")
    report = parity_report(dump, deep_sample())
    parameters = report["sections"]["devices"]["fields"]["parameters"]
    assert parameters["coverage"] == "partial"
    assert parameters["native_unavailable"] == 1
    assert "devices.parameters" in report["missing_native_fields"]
    assert not report["native_parity"]


def test_nullable_routing_and_track_color_are_native_when_explicit():
    report = parity_report(deep_native(), deep_sample())
    for name in ["source", "destination", "sidechain"]:
        assert report["sections"]["routing"]["fields"][name]["coverage"] == "native"
    assert report["sections"]["tracks"]["fields"]["color"]["coverage"] == "native"


def test_missing_expression_collections_remain_depth_gaps():
    dump = deep_native()
    del dump["main_tracks"][0]["children"][0]["clips"][0]["notes"][0]["expressions"]
    report = parity_report(dump, deep_sample())
    counts = report["sections"]["automation"]["counts"]
    assert counts["note_expressions_lanes"]["native"] is None
    assert any(g["count"] == "note_expressions_points" for g in report["depth_gaps"])
    assert report["structure_matches"]
    assert not report["native_parity"]


def test_converter_preserves_interleaved_device_types():
    from openwig.java_read import _children
    devices = _xml_node(ET.fromstring('<Devices><BuiltinDevice deviceID="one"/>'
                                     '<Vst3Plugin deviceID="two"/>'
                                     '<BuiltinDevice deviceID="three"/></Devices>'))
    assert [d["attributes"]["deviceID"] for d in _children(devices)] == ["one", "two", "three"]
    assert len(devices["BuiltinDevice"]) == 2


def test_expression_points_supply_coverage_without_arranger_lanes():
    dump = deep_native()
    for track in [dump["main_tracks"][0], *dump["main_tracks"][0]["children"], dump["master"]]:
        track["automation"] = []
    dump["metadata"]["writer"] = "Example Writer"
    dump["main_tracks"][0]["children"][0]["clips"][0]["notes"][0]["repeat_count"] = 2
    fields = parity_report(dump, deep_sample())["sections"]
    assert fields["automation"]["fields"]["time"]["coverage"] == "native"
    assert fields["automation"]["fields"]["value"]["coverage"] == "native"
    assert fields["metadata"]["fields"]["songwriter"]["coverage"] == "native"
    assert fields["notes"]["fields"]["repeats"]["coverage"] == "native"


def test_value_checker_detects_identity_expression_and_warp_corruption():
    from java_reader_checks import assert_reader_values, reader_value_report
    dump, daw = deep_native(), deep_sample()
    device = dump["main_tracks"][0]["children"][0]["devices"][0]
    device.update(device_id="org.example.synth", device_name="Example Synth", role="instrument")
    report = assert_reader_values(dump, daw)
    assert report["checked"]["warp_markers"] == 2
    assert report["checked"]["note_expression_points"] == 2
    assert report["checked"]["audio_expression_points"] == 1
    note = dump["main_tracks"][0]["children"][0]["clips"][0]["notes"][0]
    note["expressions"][0]["points"][0]["value"] = 0.0000009
    assert assert_reader_values(dump, daw)["passed"]  # within XML rounding tolerance
    note["expressions"][0]["points"][0]["value"] = 0.01
    event = dump["main_tracks"][0]["children"][1]["clips"][0]["audio_events"][0]
    event["warp_markers"][1]["content_time"] = 2
    device["device_id"] = "wrong"
    report = reader_value_report(dump, daw)
    assert len(report["mismatches"]) == 3
    with pytest.raises(AssertionError, match="3 reader value mismatches"):
        assert_reader_values(dump, daw)


def test_native_automation_beyond_export_is_diagnostic_not_structural_failure():
    dump, daw = deep_native(), deep_sample()
    lane = {"target_name": "Hidden Nested Macro", "device": 0,
            "target_path": "CONTENTS/CHAIN_LIST/CHAIN0/DEVICE_CHAIN/1:MACRO",
            "points": [{"time": 0, "value": 0.9}]}
    dump["main_tracks"][0]["children"][0]["automation"].append(lane)
    report = parity_report(dump, daw)
    assert report["structure_matches"]
    assert report["sections"]["automation"]["counts"]["lanes"]["native"] == 1
    baseline = report["diagnostics"]["automation_baseline"]
    assert baseline["native_raw_lanes"] == 2
    assert len(baseline["extra_native"]) == 1
    assert baseline["extra_native"][0]["target_name"] == "Hidden Nested Macro"
    assert not baseline["missing"]


def test_native_extra_lane_cannot_hide_removed_exported_automation():
    dump, daw = deep_native(), deep_sample()
    dump["main_tracks"][0]["children"][0]["automation"] = [
        {"target_name": "Other", "target_path": "OTHER", "points": [{"time": 0, "value": 0.1}]}]
    report = parity_report(dump, daw)
    assert not report["structure_matches"]
    assert report["diagnostics"]["automation_baseline"]["missing"][0]["target_name"] == "cutoff"
    assert report["sections"]["automation"]["counts"]["lanes"]["native"] == 0


def test_corrupt_baseline_point_is_a_value_failure_even_with_matching_counts():
    from java_reader_checks import assert_reader_values
    dump, daw = deep_native(), deep_sample()
    dump["main_tracks"][0]["children"][0]["devices"][0].update(
        device_id="org.example.synth", device_name="Example Synth", role="instrument")
    lane = dump["main_tracks"][0]["children"][0]["automation"][0]
    lane["points"][0]["value"] = "broken"
    report = parity_report(dump, daw)
    assert report["structure_matches"]
    assert report["value_mismatches"][0]["section"] == "automation"
    assert not report["native_parity"]
    with pytest.raises(AssertionError, match="reader value mismatches"):
        assert_reader_values(dump, daw)


def test_note_content_wrapped_in_lanes_keeps_notes_and_expressions_in_scope():
    from java_reader_checks import reader_value_report
    dump, daw = deep_native(), deep_sample()
    clip = daw["project"]["Arrangement"][0]["Lanes"][0]["Lanes"][0]["Clips"][0]["Clip"][0]
    notes = clip.pop("Notes")
    # Clip automation wraps content in Lanes; a nested Clip remains a
    # separate event and must not add its Note to the arranger-root count.
    clip["Lanes"] = [{"tag": "Lanes", "attributes": {}, "Notes": notes,
                      "Clips": [_xml_node(ET.fromstring('<Clips><Clip time="0"><Notes>'
                                                        '<Note key="99"/></Notes></Clip></Clips>'))],
                      "Points": [_xml_node(ET.fromstring('<Points unit="normalized">'
                           '<Target expression="pressure"/><RealPoint time="0" value="0.5"/></Points>'))]}]
    report = parity_report(dump, daw)
    assert report["sections"]["notes"]["counts"]["arranger_direct"]["match"]
    assert report["sections"]["notes"]["counts"]["arranger_direct"]["export"] == 1
    assert report["diagnostics"]["xml_arranger_nested_notes"] == 1
    assert report["sections"]["automation"]["counts"]["clip_expressions_lanes"]["export"] == 1
    values = reader_value_report(dump, daw)
    assert values["checked"]["notes"] == 1
    assert values["checked"]["note_expression_points"] == 2
    assert not any(".note_count" in m["location"] for m in values["mismatches"])


def test_time_signature_points_compare_numerator_and_denominator_not_null_value():
    from java_reader_checks import reader_value_report
    dump, daw = deep_native(), deep_sample()
    signature = daw["project"]["Transport"][0]["TimeSignature"][0]
    signature["attributes"].update(id="signature", name="Time Signature")
    lanes = daw["project"]["Arrangement"][0]["Lanes"][0]
    lanes["Lanes"].append(_xml_node(ET.fromstring(
        '<Lanes track="master"><Points><Target parameter="signature"/>'
        '<TimeSignaturePoint time="4" numerator="3" denominator="8"/></Points></Lanes>')))
    lane = {"target_name": "Time Signature", "target_path": "time_signature", "points": [
        {"kind": "time_signature", "time": 4, "value": None, "numerator": 3, "denominator": 8}]}
    dump["master"]["automation"] = [lane]
    report = parity_report(dump, daw)
    assert report["structure_matches"]
    assert report["sections"]["automation"]["counts"]["lanes"]["native"] == 2
    assert not report["value_mismatches"]
    values = reader_value_report(dump, daw)
    assert values["checked"]["arranger_automation_points"] == 3
    assert not any("time_signature" in m["location"] or "Time Signature" in m["location"]
                   for m in values["mismatches"])
    lane["points"][0]["numerator"] = 4
    report = parity_report(dump, daw)
    assert report["value_mismatches"][0]["target_name"] == "Time Signature"
    values = reader_value_report(dump, daw)
    assert any(m["location"].endswith(".numerator") for m in values["mismatches"])
