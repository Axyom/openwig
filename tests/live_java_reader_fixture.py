"""Capture a small JavaSong-authored fixture in an open disposable project.

Run from the repository root with PYTHONPATH=src and --capture. This appends
three uniquely named tracks, exports to scratch, and writes only their trimmed
XML to the generated fixture directory. It never clears, saves, closes, or
launches a project. Close the disposable project WITHOUT saving afterwards.
"""

from __future__ import annotations

import argparse
import copy
import json
import time
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from openwig import JavaSong, Note
from openwig.bridge import BridgeClient


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "tests/fixtures/dawproject-sample/generated"
ARCHIVE = ROOT / "scratch/reader-fixture.dawproject"


def trim_export(source: ET.Element, names: dict[str, str]) -> ET.Element:
    """Whitelist only this capture's tracks and timelines, never source metadata."""
    structure = source.find("Structure")
    assert structure is not None, "export has no Structure"
    selected = {}
    for role, name in names.items():
        matches = [track for track in structure.iter("Track") if track.get("name") == name]
        assert len(matches) == 1, (role, name, len(matches))
        selected[role] = copy.deepcopy(matches[0])
    masters = [track for track in structure.findall("Track")
               if track.find("Channel") is not None
               and track.find("Channel").get("role") == "master"
               and not track.find("Channel").get("destination")]
    assert len(masters) == 1, "could not identify the exported project master"
    master = copy.deepcopy(masters[0])
    master.set("name", "Master")
    master.attrib.pop("color", None)
    for devices in master.findall(".//Devices"):
        devices.clear()
    selected_ids = {track.attrib["id"] for track in selected.values()}
    channels = {track.find("Channel").attrib["id"] for track in [*selected.values(), master]}
    for role, track in selected.items():
        track.set("name", {"instrument": "Fixture Synth", "audio": "Fixture Audio", "effect": "Fixture FX"}[role])
        for parent in track.iter():
            parent.attrib.pop("comment", None)
            for child in list(parent):
                # New tracks also get sends to any pre-existing effect tracks.
                if child.tag == "Send" and child.get("destination") not in channels:
                    parent.remove(child)
                elif child.tag == "State":
                    parent.remove(child)
    # Master settings are deliberately synthetic, not copied from its source chain.
    for child in list(master):
        if child.tag != "Channel":
            master.remove(child)
    channel = master.find("Channel")
    channel.attrib.pop("comment", None)
    channel.set("solo", "false")
    for child in list(channel):
        if child.tag not in {"Devices", "Mute", "Pan", "Volume"}:
            channel.remove(child)
        elif child.tag in {"Mute", "Pan", "Volume"}:
            child.set("value", {"Mute": "false", "Pan": "0.5", "Volume": "1.0"}[child.tag])
    master.attrib.pop("comment", None)

    out = ET.Element("Project", source.attrib)
    application = source.find("Application")
    if application is not None:
        out.append(copy.deepcopy(application))
    transport = ET.SubElement(out, "Transport")
    ET.SubElement(transport, "Tempo", value="120", unit="bpm")
    ET.SubElement(transport, "TimeSignature", numerator="4", denominator="4")
    kept_structure = ET.SubElement(out, "Structure")
    kept_structure.extend([*selected.values(), master])
    arrangement = ET.SubElement(out, "Arrangement")
    lanes = ET.SubElement(arrangement, "Lanes", timeUnit="beats")
    for item in source.findall("./Arrangement//Lanes"):
        if item.get("track") in selected_ids:
            lanes.append(copy.deepcopy(item))
    first_scene = source.find("./Scenes/Scene")
    if first_scene is not None:
        scene = ET.SubElement(ET.SubElement(out, "Scenes"), "Scene", name="Scene 1")
        scene_lanes = ET.SubElement(scene, "Lanes")
        for slot in first_scene.iter("ClipSlot"):
            if slot.get("track") in selected_ids:
                scene_lanes.append(copy.deepcopy(slot))
    assert len(list(out.iter("Track"))) == 4
    assert len(list(out.iter("Note"))) == 2, "fixture must contain exactly its two authored notes"
    ids = {node.get("id") for node in out.iter() if node.get("id")}
    for node in out.iter():
        for attribute in ("track", "destination", "reference", "parameter"):
            if node.get(attribute):
                assert node.get(attribute) in ids, (node.tag, attribute, node.get(attribute))
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", action="store_true", required=True,
                        help="append synthetic tracks to the already-open disposable project")
    parser.parse_args()
    bridge = BridgeClient(port=7778)
    bridge.start()
    try:
        assert bridge.wait_connected(60), "Java bridge did not connect"
        deadline = time.monotonic() + 120
        while not bridge.request("document.status").get("active"):
            assert time.monotonic() < deadline, "no active disposable project"
            time.sleep(1)
        time.sleep(12)
        song = JavaSong(bridge=bridge)
        prefix = "Openwig Fixture " + uuid.uuid4().hex[:10]
        names = {"instrument": prefix + " Synth", "audio": prefix + " Audio", "effect": prefix + " FX"}
        effect_index = song.effect_track(names["effect"])
        synth = song.track(names["instrument"], device="FM-4")
        synth.clip([Note(60, 0, 1, 0.8), Note(67, 2, 0.5, 0.65)], dur=4)
        song.audio_track(names["audio"])
        synth.send(effect_index, 0.3)
        deadline = time.monotonic() + 30
        while not any(item.get("path") == "OUTPUT" for item in synth.list_parameters(0)):
            assert time.monotonic() < deadline, "FM-4 parameter loading did not finish"
            time.sleep(0.25)
        dump = song.dump()
        fx = next(track for track in dump["effect_tracks"] if track["name"] == names["effect"])
        assert song.track_at("fx/" + names["effect"]).read() == fx
        authored = next(track for track in dump["main_tracks"] if track["name"] == names["instrument"])
        targeted = song.track_at(names["instrument"]).read()
        send = next(item for item in authored["sends"] if item["index"] == effect_index)
        targeted_send = next(item for item in targeted["sends"] if item["index"] == effect_index)
        assert targeted_send == send
        assert send["target"] == names["effect"]
        assert abs(send["amount"] - 0.3) < 0.01, send
        assert send["mode"] in {"AUTO", "PRE", "POST"}, send
        assert send["effective_mode"] in {"PRE", "POST"}, send
        if send["mode"] != "AUTO":
            assert send["effective_mode"] == send["mode"], send
        ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
        song.export_dawproject(ARCHIVE)
        with zipfile.ZipFile(ARCHIVE) as archive:
            raw = ET.fromstring(archive.read("project.xml"))
        raw_synth = next(track for track in raw.findall("./Structure/Track")
                         if track.get("name") == names["instrument"])
        raw_fx = next(track for track in raw.findall("./Structure/Track")
                      if track.get("name") == names["effect"])
        xml_send = next(item for item in raw_synth.findall(".//Send")
                        if item.get("destination") == raw_fx.find("Channel").get("id"))
        assert xml_send.attrib["type"].upper() == send["effective_mode"], (xml_send.attrib, send)
        trimmed = trim_export(raw, names)
        OUTPUT.mkdir(parents=True, exist_ok=True)
        ET.indent(trimmed)
        ET.ElementTree(trimmed).write(OUTPUT / "project.xml", encoding="utf-8", xml_declaration=True)
        metadata = ET.Element("MetaData")
        ET.SubElement(metadata, "Title").text = "JavaSong synthetic reader fixture"
        ET.SubElement(metadata, "Comment").text = "Only newly authored synthetic tracks; no source project metadata."
        ET.indent(metadata)
        ET.ElementTree(metadata).write(OUTPUT / "metadata.xml", encoding="utf-8", xml_declaration=True)
        (OUTPUT / "README.md").write_text(
            "Generated from Bitwig 6.1.5's native DAWproject export after creating three\n"
            "new tracks through JavaSong: FM-4 with two MIDI notes, an empty audio\n"
            "track, and an effect track receiving a 0.3 send. The source project was\n"
            "never cleared or saved. Only these tracks, their arranger lanes, the\n"
            "first scene's matching slots, and a sanitized master remain. Transport\n"
            "and metadata are synthetic; no media or plugin state is included.\n\n"
            "Regenerate on an open disposable project (close WITHOUT saving):\n\n"
            "    $env:PYTHONPATH='src'; python tests/live_java_reader_fixture.py --capture\n\n"
            f"Verified send mode {send['mode']}, effective {send['effective_mode']}; "
            "targeted effect and send reads matched the atomic dump.\n",
            encoding="utf-8")
        print(json.dumps({"fixture": str(OUTPUT), "tracks": 4, "notes": 2,
                          "send_mode": send["mode"], "effective_send_mode": send["effective_mode"]}, indent=2))
    finally:
        bridge.stop()


if __name__ == "__main__":
    main()
