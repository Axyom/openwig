"""Value checks shared by live reader verification and saved-artifact audits.

Inputs are JavaSong.dump() and java_read.export_to_dict() results. No bridge
calls or project edits occur here. XML clip wrappers are walked in document
order; duplicate sibling track paths resolve to the first track on both sides.
"""

from __future__ import annotations

from collections import Counter
import math

from openwig.java_read import (_children, _direct_clip_notes, _match_arranger_automation, _native_tracks,
                              _outer_clips, _tag, _walk)


def reader_value_report(dump: dict, daw: dict, *, atol: float = 1e-6) -> dict:
    """Compare selected exported values without treating count parity as proof.

    The tolerance is absolute: Bitwig serializes many XML values to six
    decimals. Media archive paths and original sample paths intentionally are
    not compared; exporting renames/relocates media. Parameter values with
    different units are outside this checker.
    """
    if not math.isfinite(atol) or atol < 0:
        raise ValueError("atol must be finite and nonnegative")
    root = daw["project"]
    checked = Counter()
    mismatches = []

    def compare(where, actual, expected, *, numeric=False, count="values"):
        checked[count] += 1
        if numeric:
            try:
                equal = math.isclose(float(actual), float(expected), rel_tol=0, abs_tol=atol)
            except (TypeError, ValueError):
                equal = False
        elif isinstance(expected, bool):
            equal = isinstance(actual, bool) and actual == expected
        else:
            equal = actual == expected
        if not equal:
            mismatches.append({"location": where, "native": actual, "export": expected})

    native_tracks = {}
    native_duplicates = []
    for path, track in _native_tracks(dump):
        if path in native_tracks:
            native_duplicates.append(path)
        native_tracks.setdefault(path, track)
    xml_tracks = {}
    xml_duplicates = []

    def visit_tracks(container, prefix=""):
        for track in _children(container, "Track"):
            attrs = track["attributes"]
            channel = next(_children(track, "Channel"), {})
            role = channel.get("attributes", {}).get("role")
            if (not prefix and role == "master" and not list(_children(track, "Track"))
                    and attrs.get("contentType") != "tracks"):
                path = "master"
            elif not prefix and role == "effect":
                path = "fx/" + attrs.get("name", "")
            else:
                path = prefix + attrs.get("name", "")
            if path in xml_tracks:
                xml_duplicates.append(path)
            xml_tracks.setdefault(path, track)
            visit_tracks(track, path + "/")
    visit_tracks(next(_children(root, "Structure"), {}))
    compare("track_paths", sorted(native_tracks), sorted(xml_tracks), count="track_path_sets")

    id_index = {n["attributes"]["id"]: n for n in _walk(root)
                if "id" in n.get("attributes", {})}

    def content_children(node):
        yield from _children(node)
        reference = node.get("attributes", {}).get("reference")
        if reference in id_index:
            yield id_index[reference]

    def direct_notes(clip):
        return _direct_clip_notes(clip, id_index)

    def xml_envelopes(owner):
        # Expression Points can be wrapped in Lanes, but do not take envelopes
        # from nested events/notes that are separate native entities.
        result = []
        def walk(node, top=False):
            if not top and _tag(node) in {"Clip", "Note", "Audio", "Warps"}:
                return
            if _tag(node) == "Points":
                target = next(_children(node, "Target"), {})
                expression = target.get("attributes", {}).get("expression")
                if expression is not None:
                    result.append((expression.lower(), node))
                return
            for child in _children(node):
                walk(child)
        walk(owner, True)
        return result

    def expressions(where, actual, expected, scope):
        # Envelope identity is its expression type, not serializer list order.
        # Preserve point order, including duplicate-time/discontinuous points.
        by_name = {}
        for expression in actual:
            name = expression.get("expression")
            by_name.setdefault(name, []).append(expression)
        xml_by_name = {}
        for name, envelope in expected:
            xml_by_name.setdefault(name, []).append(envelope)
        compare(where + ".types", sorted(by_name, key=str), sorted(xml_by_name),
                count=scope + "_type_sets")
        for name in sorted(set(by_name) & set(xml_by_name)):
            compare(where + "." + name + ".count", len(by_name[name]), len(xml_by_name[name]),
                    count=scope + "_counts")
            for index, (native, xml) in enumerate(zip(by_name[name], xml_by_name[name])):
                label = f"{where}.{name}[{index}]"
                checked[scope + "_lanes"] += 1
                attrs = xml["attributes"]
                if "unit" in attrs:
                    compare(label + ".unit", native.get("unit"), attrs["unit"], count=scope + "_units")
                points = [p for p in _children(xml) if _tag(p).endswith("Point")]
                compare(label + ".points", len(native.get("points", [])), len(points),
                        count=scope + "_point_counts")
                for point_index, (npoint, epoint) in enumerate(zip(native.get("points", []), points)):
                    checked[scope + "_points"] += 1
                    point_attrs = epoint["attributes"]
                    for key in ["time", "value"]:
                        compare(f"{label}.points[{point_index}].{key}", npoint.get(key),
                                point_attrs.get(key), numeric=True, count=scope + "_point_values")
                    if "interpolation" in point_attrs:
                        compare(f"{label}.points[{point_index}].interpolation",
                                npoint.get("interpolation"), point_attrs["interpolation"],
                                count=scope + "_interpolations")

    def audio_nodes(clip):
        # Match the native audio_events order by XML Audio occurrence. Locate
        # warps and expression siblings inside each event's nearest Clip.
        events = []
        def visit(node, owner=None, warps=None):
            if _tag(node) == "Clip":
                owner = node
            if _tag(node) == "Warps":
                warps = node
            if _tag(node) == "Audio":
                events.append((node, owner, warps))
                return
            for child in content_children(node):
                visit(child, owner, warps)
        visit(clip)
        return events

    clips_by_id = {}
    arrangement = next(_children(root, "Arrangement"), {})
    for track_id, clip in _outer_clips(arrangement):
        clips_by_id.setdefault(track_id, []).append(clip)

    def check_clip(where, native, xml):
        enotes = direct_notes(xml)
        compare(where + ".note_count", len(native.get("notes", [])), len(enotes), count="note_counts")
        for i, (note, enote) in enumerate(zip(native.get("notes", []), enotes)):
            checked["notes"] += 1
            expressions(f"{where}.notes[{i}].expressions", note.get("expressions", []),
                        xml_envelopes(enote), "note_expression")
        events = audio_nodes(xml)
        compare(where + ".audio_event_count", len(native.get("audio_events", [])), len(events),
                count="audio_event_counts")
        for index, (event, (audio, owner, warps)) in enumerate(zip(native.get("audio_events", []), events)):
            checked["audio_events"] += 1
            label = f"{where}.audio_events[{index}]"
            for key, xmlkey in [("algorithm", "algorithm"), ("sample_rate", "sampleRate"),
                                ("channels", "channels")]:
                if xmlkey in audio["attributes"]:
                    compare(label + "." + key, event.get(key), audio["attributes"][xmlkey],
                            numeric=key != "algorithm", count="audio_properties")
            markers = list(_children(warps or {}, "Warp"))
            compare(label + ".warp_markers", len(event.get("warp_markers", [])), len(markers),
                    count="warp_counts")
            for i, (marker, emarker) in enumerate(zip(event.get("warp_markers", []), markers)):
                checked["warp_markers"] += 1
                for key, xmlkey in [("time", "time"), ("content_time", "contentTime")]:
                    compare(f"{label}.warp_markers[{i}].{key}", marker.get(key),
                            emarker["attributes"].get(xmlkey), numeric=True, count="warp_values")
            expressions(label + ".expressions", event.get("expressions", []),
                        xml_envelopes(owner or {}), "audio_expression")

    for path in sorted(set(native_tracks) & set(xml_tracks)):
        native, xml = native_tracks[path], xml_tracks[path]
        checked["tracks"] += 1
        devices = [device for channel in _children(xml, "Channel")
                   for chain in _children(channel, "Devices") for device in _children(chain)]
        compare(path + ".devices", len(native.get("devices", [])), len(devices), count="device_counts")
        for index, (device, edevice) in enumerate(zip(native.get("devices", []), devices)):
            checked["devices"] += 1
            for key, xmlkey in [("device_id", "deviceID"), ("device_name", "deviceName"), ("role", "deviceRole")]:
                if xmlkey in edevice["attributes"]:
                    compare(f"{path}.devices[{index}].{key}", device.get(key),
                            edevice["attributes"][xmlkey], count="device_identity_values")
        clips = clips_by_id.get(xml["attributes"].get("id"), [])
        compare(path + ".clips", len(native.get("clips", [])), len(clips), count="clip_counts")
        for i, (clip, eclip) in enumerate(zip(native.get("clips", []), clips)):
            checked["clips"] += 1
            check_clip(f"{path}.clips[{i}]", clip, eclip)
    automation = _match_arranger_automation(dump, daw)
    for missing in automation["missing"]:
        mismatches.append({"location": "arranger_automation", "native": "unmatched",
                           "export": missing})
    for match in automation["matched"]:
        lane, expected = match["native"], match["export"]
        checked["arranger_automation_lanes"] += 1
        label = (str(expected["track_path"]) + ".automation."
                 + str(expected["device"]) + "." + str(expected["target_name"]))
        compare(label + ".point_count", len(lane.get("points", [])), len(expected["points"]),
                count="arranger_automation_point_counts")
        for index, (actual, exported) in enumerate(zip(lane.get("points", []), expected["points"])):
            checked["arranger_automation_points"] += 1
            keys = (["time", "numerator", "denominator"] if "numerator" in exported
                    else ["time", "value"])
            for key in keys:
                compare(f"{label}.points[{index}].{key}", actual.get(key), exported[key],
                        numeric=key == "time" or (key == "value" and not isinstance(exported[key], bool)),
                        count="arranger_automation_values")
            if "interpolation" in exported:
                compare(f"{label}.points[{index}].interpolation", actual.get("interpolation"),
                        exported["interpolation"], count="arranger_automation_interpolations")
    return {"passed": not mismatches, "checked": dict(checked), "mismatches": mismatches,
            "automation_extra_native": automation["extra_native"],
            "duplicate_paths_first_match": sorted(set(native_duplicates + xml_duplicates)),
            "absolute_tolerance": atol}


def assert_reader_values(dump: dict, daw: dict, *, atol: float = 1e-6) -> dict:
    """Assert selected value parity and return machine-readable check counts."""
    report = reader_value_report(dump, daw, atol=atol)
    assert report["passed"], (f"{len(report['mismatches'])} reader value mismatches: "
                              f"{report['mismatches'][:20]}")
    return report
