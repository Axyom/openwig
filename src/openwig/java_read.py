"""Lossless DAWproject XML view and scope-aware native-reader parity checks.

The XML view deliberately does not coerce strings, flatten singleton lists, or
discard IDs. Counts compare arranger clip *roots*, not nested audio wrappers.
Coverage describes available data, not equality of parameter values or units.
"""

from __future__ import annotations

from pathlib import Path
import math
import re
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def _xml_node(element: ET.Element) -> dict:
    node = {"tag": element.tag, "attributes": dict(element.attrib)}
    if element.text and element.text.strip():
        node["text"] = element.text
    for child in element:
        node.setdefault("child_order", []).append(child.tag)
        node.setdefault(child.tag, []).append(_xml_node(child))
    return node


def export_to_dict(song, path=None) -> dict:
    """Export and parse project.xml and optional metadata.xml.

    Returns ``{"project": node, "metadata": node | None}``. Each node has
    ``tag``, ``attributes`` (including IDs), optional ``text``, and child lists
    keyed by their XML tag. An explicit archive is retained; an implicit archive
    and its temporary directory are removed even when export or parsing fails.
    ``child_order`` preserves interleaving of different child tags, such as
    native/plugin device order. XML namespaces and extensions are preserved.
    """
    def read(target):
        song.export_dawproject(target)
        with ZipFile(target) as archive:
            project = _xml_node(ET.fromstring(archive.read("project.xml")))
            metadata = (_xml_node(ET.fromstring(archive.read("metadata.xml")))
                        if "metadata.xml" in archive.namelist() else None)
        return {"project": project, "metadata": metadata}

    if path is not None:
        return read(Path(path).resolve())
    with tempfile.TemporaryDirectory(prefix="openwig-reader-") as directory:
        return read(Path(directory) / "project.dawproject")


def _tag(node):
    return node.get("tag", "").rsplit("}", 1)[-1]


def _children(node, tag=None):
    if "child_order" in node:
        indices = {}
        for name in node["child_order"]:
            index = indices.get(name, 0)
            indices[name] = index + 1
            children = node.get(name, [])
            if index >= len(children):
                continue
            child = children[index]
            if tag is None or _tag(child) == tag:
                yield child
        # Keep hand-authored/extended views usable if callers append a new
        # child without rebuilding the serializer's order metadata.
        for name, value in node.items():
            if isinstance(value, list):
                for child in value[indices.get(name, 0):]:
                    if isinstance(child, dict) and (tag is None or _tag(child) == tag):
                        yield child
        return
    for value in node.values():
        if isinstance(value, list):
            for child in value:
                if isinstance(child, dict) and (tag is None or _tag(child) == tag):
                    yield child


def _walk(node, tag=None):
    if tag is None or _tag(node) == tag:
        yield node
    for child in _children(node):
        yield from _walk(child, tag)


def _outer_clips(node, track=None):
    track = node.get("attributes", {}).get("track", track)
    if _tag(node) == "Clip":
        yield track, node
        return
    for child in _children(node):
        yield from _outer_clips(child, track)


def _native_tracks(dump):
    def descend(tracks, prefix=""):
        for track in tracks:
            path = prefix + track.get("name", "")
            yield path, track
            yield from descend(track.get("children", []), path + "/")
    yield from descend(dump.get("main_tracks", []))
    yield from descend(dump.get("effect_tracks", []), "fx/")
    if isinstance(dump.get("master"), dict):
        yield "master", dump["master"]


def _direct_clip_notes(clip, id_index=None):
    """Read note content through Lanes, stopping at nested Clip boundaries."""
    id_index = id_index or {}
    seen = set()
    def content(node):
        if id(node) in seen:
            return
        seen.add(id(node))
        if _tag(node) == "Notes":
            yield from _children(node, "Note")
            return
        if _tag(node) not in {"Clip", "Lanes"}:
            return
        for child in _children(node):
            if _tag(child) in {"Notes", "Lanes"}:
                yield from content(child)
        reference = node.get("attributes", {}).get("reference")
        if reference in id_index and _tag(id_index[reference]) in {"Notes", "Lanes"}:
            yield from content(id_index[reference])
    return list(content(clip))


def _export_points(lane):
    """Decode the small point subset shared by exported/native automation."""
    points = []
    for point in _children(lane):
        tag = _tag(point)
        if not tag.endswith("Point"):
            continue
        attrs = point["attributes"]
        value = attrs.get("value")
        if tag == "BoolPoint":
            value = value == "true"
        elif value is not None:
            try:
                value = float(value)
            except ValueError:
                pass
        decoded = {"time": float(attrs["time"]), "value": value}
        if tag == "TimeSignaturePoint":
            decoded.update(numerator=int(attrs["numerator"]), denominator=int(attrs["denominator"]))
        if "interpolation" in attrs:
            decoded["interpolation"] = attrs["interpolation"]
        points.append(decoded)
    return points


def _points_equal(native, exported, atol=1e-6):
    if len(native) != len(exported):
        return False
    for actual, expected in zip(native, exported):
        signature = "numerator" in expected or "denominator" in expected
        for key in (["time", "numerator", "denominator"] if signature else ["time", "value"]):
            a, e = actual.get(key), expected.get(key)
            if key in {"numerator", "denominator"}:
                if not isinstance(a, int) or isinstance(a, bool) or a != e:
                    return False
            elif isinstance(e, bool):
                if not isinstance(a, bool) or a != e:
                    return False
            else:
                try:
                    if not math.isclose(float(a), float(e), rel_tol=0, abs_tol=atol):
                        return False
                except (ValueError, TypeError):
                    return False
        if "interpolation" in expected and actual.get("interpolation") != expected["interpolation"]:
            return False
    return True


def _match_arranger_automation(dump, daw):
    """Match the exported arranger subset; native-only lanes remain extras.

    Identity is scoped by track and optional top-level device/parameter ID.
    Names choose a unique target before looking at values, so corrupted points
    cannot make a missing baseline lane disappear into the native-only set.
    A point fingerprint disambiguates otherwise ambiguous labels; it never
    authorizes an ambiguous match. All missing exported lanes remain explicit.
    """
    root = daw["project"]
    parents = {id(child): node for node in _walk(root) for child in _children(node)}
    ids = {n["attributes"]["id"]: n for n in _walk(root)
           if "id" in n.get("attributes", {})}
    paths = {}
    devices = {}
    def visit(container, prefix=""):
        for track in _children(container, "Track"):
            attrs = track["attributes"]
            channel = next(_children(track, "Channel"), {})
            role = channel.get("attributes", {}).get("role")
            path = prefix + attrs.get("name", "")
            if not prefix and role == "master" and not list(_children(track, "Track")) and attrs.get("contentType") != "tracks":
                path = "master"
            elif not prefix and role == "effect":
                path = "fx/" + attrs.get("name", "")
            paths[attrs.get("id")] = path
            for chain in _children(channel, "Devices"):
                for index, device in enumerate(_children(chain)):
                    devices[id(device)] = index
            visit(track, path + "/")
    visit(next(_children(root, "Structure"), {}))
    native = []
    for path, track in _native_tracks(dump):
        native.extend((path, lane) for lane in track.get("automation", []))
    if "automation" in dump:
        native = [(lane.get("track_path"), lane) for lane in dump["automation"]]
    used = set()
    matched, missing = [], []
    normalize = lambda name: re.sub(r"[^a-z0-9]", "", str(name or "").lower())
    for xml in _walk(next(_children(root, "Arrangement"), {}), "Points"):
        ancestor = parents.get(id(xml))
        track_id, in_clip = None, False
        while ancestor is not None:
            in_clip |= _tag(ancestor) in {"Clip", "Note"}
            track_id = track_id or ancestor.get("attributes", {}).get("track")
            ancestor = parents.get(id(ancestor))
        if in_clip:
            continue
        target = next(_children(xml, "Target"), {})
        parameter = ids.get(target.get("attributes", {}).get("parameter"), {})
        attrs = parameter.get("attributes", {})
        owner = parameter
        device_index = None
        while owner:
            if id(owner) in devices:
                device_index = devices[id(owner)]
                break
            owner = parents.get(id(owner))
        path = paths.get(track_id)
        expected = {"track_path": path, "device": device_index,
                    "target_name": attrs.get("name", attrs.get("id", _tag(parameter))),
                    "parameter_id": attrs.get("parameterID"),
                    "unit": xml.get("attributes", {}).get("unit", attrs.get("unit")),
                    "points": _export_points(xml), "xml": xml}
        candidates = []
        for index, (native_path, lane) in enumerate(native):
            if index in used or native_path != path:
                continue
            target_path = str(lane.get("target_path") or "")
            if "MODULATORS/" in target_path or (not target_path.startswith("PROJECT/") and "DEVICE_CHAIN/" in target_path):
                continue
            if device_index is not None and "device" in lane and lane["device"] != device_index:
                continue
            if device_index is None and "device" in lane:
                continue
            if attrs.get("parameterID") is not None and lane.get("parameter_id") is not None and str(lane["parameter_id"]) != attrs["parameterID"]:
                continue
            if expected["unit"] is not None and lane.get("unit") is not None and lane["unit"] != expected["unit"]:
                continue
            candidates.append(index)
        named = [index for index in candidates if normalize(native[index][1].get("target_name")) == normalize(expected["target_name"])]
        if named:
            candidates = named
        if not named or len(candidates) > 1:
            candidates = [index for index in candidates if _points_equal(native[index][1].get("points", []), expected["points"])]
        if len(candidates) == 1:
            index = candidates[0]
            used.add(index)
            matched.append({"native": native[index][1], "export": expected})
        else:
            missing.append({"track_path": path, "device": device_index,
                            "target_name": expected["target_name"],
                            "parameter_id": expected["parameter_id"],
                            "point_count": len(expected["points"]),
                            "reason": "ambiguous" if candidates else "missing"})
    return {"matched": matched, "missing": missing,
            "extra_native": [{"track_path": path, "target_name": lane.get("target_name"),
                              "target_path": lane.get("target_path"), "point_count": len(lane.get("points", []))}
                             for index, (path, lane) in enumerate(native) if index not in used],
            "native_raw_lanes": len(native),
            "native_raw_points": sum(len(lane.get("points", [])) for _, lane in native)}


def parity_report(dump: dict, daw: dict) -> dict:
    """Return counts, field coverage and loud comparable-structure failures.

    ``structure_mismatches`` contains only scopes represented by both readers.
    ``depth_gaps`` contains missing native collections; absent is distinct from
    a present empty collection. XML raw totals and nested content are retained
    in diagnostics, never compared to top-level native clips/notes. Field
    coverage is schema-informed (Bitwig 6.1.5 libs.jar JAXB), and partial means
    some entities omit the value. It does not assert semantic/value parity.
    """
    root = daw["project"]
    native = list(_native_tracks(dump))
    structure = next(_children(root, "Structure"), {})
    exported = []

    def tracks(node, prefix=""):
        for track in _children(node, "Track"):
            attrs = track["attributes"]
            channel = next(_children(track, "Channel"), {})
            role = channel.get("attributes", {}).get("role")
            # Group channels also use role=master. Only a root leaf master is
            # the project's master channel, not every group bus.
            children = list(_children(track, "Track"))
            if not prefix and role == "master" and not children and attrs.get("contentType") != "tracks":
                path = "master"
            elif not prefix and role == "effect":
                path = "fx/" + attrs.get("name", "")
            else:
                path = prefix + attrs.get("name", "")
            exported.append((path, track))
            tracks(track, path + "/")
    tracks(structure)
    arrangement = next(_children(root, "Arrangement"), {})
    scenes_node = next(_children(root, "Scenes"), {})
    outer = list(_outer_clips(arrangement))
    native_clips = [clip for _, t in native for clip in t.get("clips", [])]
    native_notes = [note for c in native_clips for note in c.get("notes", [])]
    id_index = {n["attributes"]["id"]: n for n in _walk(root)
                if "id" in n.get("attributes", {})}

    def direct_clip_notes(clip):
        return _direct_clip_notes(clip, id_index)
    # Direct Notes are the content represented by native readClip. Notes in
    # nested audio/event containers are a separate depth, as are launcher notes.
    direct_notes = [note for _, c in outer for note in direct_clip_notes(c)]
    native_devices = [d for _, t in native for d in t.get("devices", [])]
    xml_devices = [d for _, t in exported for ch in _children(t, "Channel")
                   for ds in _children(ch, "Devices") for d in _children(ds)]
    report = {"schema_version": 1, "sections": {}, "structure_mismatches": [],
              "depth_gaps": [], "missing_native_fields": []}

    def section(name):
        return report["sections"].setdefault(name, {"counts": {}, "fields": {},
                                                    "missing_native_fields": []})

    def count(sec, name, n, e, comparable=True):
        comparable = comparable and n is not None and e is not None
        item = {"native": n, "export": e, "comparable": comparable,
                "match": n == e if comparable else None}
        section(sec)["counts"][name] = item
        if comparable and n != e:
            report["structure_mismatches"].append({"section": sec, "count": name,
                                                   "native": n, "export": e})
        elif not comparable and e:
            report["depth_gaps"].append({"section": sec, "count": name,
                                         "native": n, "export": e})

    def field(sec, name, entities, key=None, *, supported=False, nullable=False,
              incomplete=None):
        values = [e.get(key or name) for e in entities]
        present = (sum((key or name) in e for e in entities) if nullable else
                   sum(v is not None and v != "" for v in values))
        coverage = ("native" if (values and present == len(values)) else
                    "partial" if present else "partial" if supported else "export-only")
        unavailable = sum(bool(incomplete(e)) for e in entities) if incomplete else 0
        if unavailable:
            coverage = "partial"
        section(sec)["fields"][name] = {"coverage": coverage,
                                          "native_present": present,
                                          "native_total": len(values),
                                          "native_unavailable": unavailable}
        if coverage != "native":
            full = sec + "." + name
            section(sec)["missing_native_fields"].append(full)
            report["missing_native_fields"].append(full)

    nt = [t for _, t in native]
    count("tracks", "all", len(native), len(exported))
    count("groups", "all", sum(bool(t.get("is_group")) for t in nt),
          sum(t["attributes"].get("contentType") == "tracks" for _, t in exported))
    count("devices", "top_level", len(native_devices), len(xml_devices))
    count("clips", "arranger_roots", len(native_clips), len(outer))
    count("notes", "arranger_direct", len(native_notes), len(direct_notes))
    count("markers", "cue", len(dump.get("cue_markers", [])),
          len(list(_walk(arrangement, "Marker"))))
    # Per-path comparison detects compensating omissions that project totals
    # cannot catch. First sibling wins, matching path addressing semantics.
    native_by_path = {}
    for path, track in native:
        native_by_path.setdefault(path, track)
    xml_by_path = {}
    for path, track in exported:
        xml_by_path.setdefault(path, track)
    missing_paths = sorted(set(xml_by_path) - set(native_by_path))
    extra_paths = sorted(set(native_by_path) - set(xml_by_path))
    if missing_paths or extra_paths:
        report["structure_mismatches"].append({"section": "tracks", "count": "paths",
                                               "missing": missing_paths, "extra": extra_paths})
    clips_by_track = {}
    for track_id, clip in outer:
        clips_by_track.setdefault(track_id, []).append(clip)
    for path in sorted(set(native_by_path) & set(xml_by_path)):
        n = native_by_path[path]
        e = xml_by_path[path]
        clips = clips_by_track.get(e["attributes"].get("id"), [])
        count("clips", "track:" + path, len(n.get("clips", [])), len(clips))
        count("notes", "track:" + path,
              sum(len(c.get("notes", [])) for c in n.get("clips", [])),
              sum(len(direct_clip_notes(c)) for c in clips))
        count("devices", "track:" + path, len(n.get("devices", [])),
              sum(len(list(_children(ds))) for ch in _children(e, "Channel")
                  for ds in _children(ch, "Devices")))

    native_scenes = dump.get("scenes")
    slots_present = all("clip_slots" in t for t in nt) and bool(nt)
    slots = [slot for t in nt for slot in t.get("clip_slots", [])]
    count("scenes/launcher", "scenes", len(native_scenes) if native_scenes is not None else None,
          len(list(_walk(scenes_node, "Scene"))))
    count("scenes/launcher", "slots", len(slots) if slots_present else None,
          len(list(_walk(scenes_node, "ClipSlot"))))
    launcher_clips = [s["clip"] for s in slots if isinstance(s.get("clip"), dict)]
    xml_launcher_clips = list(_outer_clips(scenes_node))
    count("scenes/launcher", "clips", len(launcher_clips) if slots_present else None,
          len(xml_launcher_clips))
    count("scenes/launcher", "notes",
          sum(len(c.get("notes", [])) for c in launcher_clips) if slots_present else None,
          sum(len(direct_clip_notes(c)) for _, c in xml_launcher_clips))
    # Points under notes/audio clips are expression envelopes, not arranger
    # automation lanes. Audio envelopes may be siblings of Warps/Audio rather
    # than descendants of Audio, so classify them using their nearest Clip.
    parents = {id(child): node for node in _walk(root) for child in _children(node)}

    def point_scope(points):
        ancestor = parents.get(id(points))
        clip = None
        while ancestor is not None:
            if _tag(ancestor) == "Note":
                return "note_expressions"
            if clip is None and _tag(ancestor) == "Clip":
                clip = ancestor
            ancestor = parents.get(id(ancestor))
        if clip is not None:
            return "audio_expressions" if any(_walk(clip, "Audio")) else "clip_expressions"
        return "arranger"

    xml_point_scopes = {name: [] for name in
                        ["arranger", "note_expressions", "audio_expressions", "clip_expressions"]}
    for points in _walk(root, "Points"):
        xml_point_scopes[point_scope(points)].append(points)
    point_tags = {"RealPoint", "BoolPoint", "EnumPoint", "IntegerPoint", "TimeSignaturePoint"}

    def xml_point_count(lanes):
        return sum(sum(_tag(p) in point_tags for p in _children(lane)) for lane in lanes)

    lanes_present = bool(nt) and all("automation" in track for track in nt)
    lanes = [lane for track in nt for lane in track.get("automation", [])]
    # Retain compatibility with explicit project-level arranger lane payloads.
    if "automation" in dump:
        lanes_present = True
        lanes = dump["automation"]
    automation_match = _match_arranger_automation(dump, daw)
    baseline_lanes = [match["native"] for match in automation_match["matched"]]
    count("automation", "lanes", len(baseline_lanes) if lanes_present else None,
          len(xml_point_scopes["arranger"]))
    count("automation", "points",
          sum(len(lane.get("points", [])) for lane in baseline_lanes) if lanes_present else None,
          xml_point_count(xml_point_scopes["arranger"]))
    report["value_mismatches"] = [
        {"section": "automation", "track_path": match["export"]["track_path"],
         "device": match["export"]["device"], "target_name": match["export"]["target_name"]}
        for match in automation_match["matched"]
        if not _points_equal(match["native"].get("points", []), match["export"]["points"])]

    all_clips = native_clips + launcher_clips
    all_notes = [note for clip in all_clips for note in clip.get("notes", [])]
    audio_clips = [clip for clip in all_clips if clip.get("content") == "audio"
                   or "audio_events" in clip]
    events_present = bool(audio_clips) and all("audio_events" in c for c in audio_clips)
    audio_events = [event for clip in audio_clips for event in clip.get("audio_events", [])]
    count("clips", "audio_events", len(audio_events) if events_present else None,
          len(list(_walk(root, "Audio"))))
    warps_present = events_present and all("warp_markers" in e for e in audio_events)
    count("clips", "warp_markers",
          sum(len(e.get("warp_markers", [])) for e in audio_events) if warps_present else None,
          len(list(_walk(root, "Warp"))))
    expressions_by_scope = {
        "note_expressions": (all_notes, bool(all_notes) and all("expressions" in n for n in all_notes)),
        "audio_expressions": (audio_events, events_present and all("expressions" in e for e in audio_events)),
        "clip_expressions": (all_clips, bool(all_clips) and all("expressions" in c for c in all_clips)),
    }
    native_expressions = {}
    for scope, (owners, present) in expressions_by_scope.items():
        envelopes = [envelope for owner in owners for envelope in owner.get("expressions", [])]
        native_expressions[scope] = envelopes
        count("automation", scope + "_lanes", len(envelopes) if present else None,
              len(xml_point_scopes[scope]))
        count("automation", scope + "_points",
              sum(len(envelope.get("points", [])) for envelope in envelopes) if present else None,
              xml_point_count(xml_point_scopes[scope]))
    count("routing", "sends", sum(len(t.get("sends", [])) for t in nt),
          len(list(_walk(structure, "Send"))))
    count("transport", "objects", int("transport" in dump), len(list(_children(root, "Transport"))))
    metadata = daw.get("metadata")
    count("metadata", "nonempty_fields", sum(bool(v) for v in dump.get("metadata", {}).values()),
          sum(bool(n.get("text", "").strip()) for n in _children(metadata or {})), False)

    for name, key in [("name", "name"), ("color", "color"), ("loaded", "is_active"),
                      ("contentType", "track_type"), ("volume", "volume"),
                      ("pan", "pan"), ("mute", "mute"), ("solo", "solo"),
                      ("audioChannels", "audio_channels"), ("comment", "comment")]:
        field("tracks", name, nt, key, nullable=name == "color")
    field("groups", "children", [t for t in nt if t.get("is_group")], supported=True)
    for name, key in [("name", "display_name"), ("enabled", "enabled"), ("loaded", "active"),
                      ("deviceID", "device_id"), ("deviceName", "device_name"),
                      ("deviceVendor", "vendor"), ("deviceRole", "role"),
                      ("pluginVersion", "plugin_version"), ("state", "state"),
                      ("parameters", "parameters"), ("nested_chains", "chains")]:
        field("devices", name, native_devices, key,
              incomplete=(lambda d: str(d.get("parameters_status", "")).startswith("unavailable"))
              if name == "parameters" else None)
    for name, key in [("name", "name"), ("color", "color"), ("time", "start"),
                      ("duration", "duration"), ("enable", "muted"),
                      ("playStart", "play_start_offset"), ("loopStart", "loop_start"),
                      ("loopEnd", "loop_duration"), ("fadeInTime", "fade_in"),
                      ("fadeOutTime", "fade_out"), ("sample_file", "file"),
                      ("warp_markers", "warp_markers"), ("algorithm", "algorithm"),
                      ("sampleRate", "sample_rate"), ("channels", "channels"),
                      ("contentTimeUnit", "content_time_unit"), ("playStop", "play_stop"),
                      ("fadeTimeUnit", "fade_time_unit"), ("reference", "reference")]:
        applicable = (audio_events if name in {"sample_file", "warp_markers", "algorithm",
                                               "sampleRate", "channels"} else
                      audio_clips if name in {"fadeInTime", "fadeOutTime"} else native_clips)
        field("clips", name, applicable, key)
    field("clips", "audio_events", audio_clips)
    for name, key in [("channel", "channel"), ("key", "key"), ("time", "start"),
                      ("duration", "duration"), ("velocity", "on_velocity"),
                      ("releaseVelocity", "off_velocity"), ("expressions", "expressions"),
                      ("chance", "chance"), ("repeats", "repeat_count"),
                      ("occurrence", "occurrence"), ("recurrence", "recurrence")]:
        field("notes", name, native_notes, key)
    for name, key in [("target", "target_path"), ("target_name", "target_name"),
                      ("points", "points"), ("unit", "unit"), ("time_unit", "time_unit")]:
        field("automation", name, lanes, key)
    points = [point for envelope in [*lanes, *(envelope for envelopes in
              native_expressions.values() for envelope in envelopes)]
              for point in envelope.get("points", [])]
    for name in ["interpolation", "time", "value", "kind"]:
        field("automation", name, points)
    for scope, envelopes in native_expressions.items():
        for name in ["expression", "unit", "points"]:
            field("automation", scope + "." + name, envelopes, name)
    for name in ["destination", "source"]:
        field("routing", name, nt, nullable=True)
    field("routing", "sidechain", native_devices, nullable=True)
    sends = [s for t in nt for s in t.get("sends", [])]
    for name, key in [("send_destination", "target"), ("send_volume", "amount"),
                      ("send_mode", "mode"), ("effective_send_mode", "effective_mode"),
                      ("send_pan", "pan"), ("send_enable", "enable")]:
        field("routing", name, sends, key)
    for name in ["name", "color"]:
        field("scenes/launcher", "scene_" + name, native_scenes or [], name)
    for name in ["has_stop", "clip"]:
        field("scenes/launcher", name, slots, nullable=name == "clip")
    for name, key in [("name", "name"), ("color", "color"), ("time", "time")]:
        field("markers", name, dump.get("cue_markers", []), key)
    for name in ["tempo", "time_signature", "groove_enabled", "shuffle"]:
        field("transport", name, [dump.get("transport", {})])
    for name in ["title", "artist", "album", "original_artist", "composer", "songwriter",
                 "producer", "arranger", "year", "genre", "copyright", "website", "comment"]:
        field("metadata", name, [dump.get("metadata", {})],
              "writer" if name == "songwriter" else name)
    report["diagnostics"] = {
        "xml_total_clips": len(list(_walk(root, "Clip"))),
        "xml_arranger_nested_clips": len(list(_walk(arrangement, "Clip"))) - len(outer),
        "xml_launcher_clips": len(list(_walk(scenes_node, "Clip"))),
        "xml_total_notes": len(list(_walk(root, "Note"))),
        "xml_arranger_nested_notes": sum(len(list(_walk(c, "Note"))) -
                                          len(_direct_clip_notes(c))
                                          for _, c in outer),
        "xml_launcher_notes": len(list(_walk(scenes_node, "Note"))),
        "xml_warp_markers": len(list(_walk(root, "Warp"))),
        "xml_automation_points": sum(len(list(_walk(root, tag))) for tag in
                                     ["RealPoint", "BoolPoint", "EnumPoint", "IntegerPoint", "TimeSignaturePoint"]),
        "xml_point_scopes": {scope: {"lanes": len(lanes), "points": xml_point_count(lanes)}
                             for scope, lanes in xml_point_scopes.items()},
        "automation_baseline": {key: value for key, value in automation_match.items() if key != "matched"},
        "coverage_checks_values": False,
        "duplicate_native_paths": sorted({p for p, _ in native if sum(q == p for q, _ in native) > 1}),
        "duplicate_export_paths": sorted({p for p, _ in exported if sum(q == p for q, _ in exported) > 1}),
        "schema_source": "Bitwig 6.1.5 libs.jar com.bitwig.dawproject JAXB classes",
    }
    report["structure_matches"] = not report["structure_mismatches"]
    report["native_parity"] = (report["structure_matches"] and not report["depth_gaps"]
                               and not report["missing_native_fields"] and not report["value_mismatches"])
    return report
