"""Read-only parity probe. Open a project in the scratch Bitwig copy first.

Never creates tracks, saves, clears, or edits the document. Private artifacts
stay in scratch/. Run with PYTHONPATH=src from the repository root.
"""
import json
import time
import argparse
from pathlib import Path
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from openwig.java_read import export_to_dict, parity_report
from java_reader_checks import assert_reader_values


def tracks(dump):
    def descend(items):
        for item in items:
            yield item
            yield from descend(item.get("children", []))
    yield from descend(dump["main_tracks"])
    yield from descend(dump["effect_tracks"])
    yield dump["master"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-first-track")
    args = parser.parse_args()
    out = Path("scratch")
    bridge = BridgeClient(port=7778)
    bridge.start()
    try:
        assert bridge.wait_connected(60), "Java bridge did not start"
        deadline = time.monotonic() + 120
        while not bridge.request("document.status")["active"]:
            assert time.monotonic() < deadline, "no active document"
            time.sleep(1)
        song = JavaSong(bridge=bridge)
        previous, settled_since = None, time.monotonic()
        # Loading can continue after document.status becomes active. Require
        # twelve seconds of stable native data from the intended document.
        while True:
            current = song.dump()
            expected = not args.expected_first_track or (
                current["main_tracks"] and current["main_tracks"][0]["name"] == args.expected_first_track)
            if current != previous or not expected:
                settled_since = time.monotonic()
            if expected and time.monotonic() - settled_since >= 12:
                break
            assert time.monotonic() < deadline, "project did not settle"
            previous = current
            time.sleep(2)
        before = export_to_dict(song, out / "reader-before.dawproject")
        dump = song.dump()
        (out / "reader-dump.json").write_text(json.dumps(dump, indent=2), encoding="utf-8")
        seen = set()
        checked = {"tracks": 0, "clips": 0, "devices": 0, "lanes": 0}
        for track in tracks(dump):
            path = track["path"]
            # Path strings deliberately resolve the first duplicate sibling.
            if path in seen:
                continue
            seen.add(path)
            handle = song.track_at(path)
            assert handle.read() == track, path
            checked["tracks"] += 1
            if track["clips"]:
                assert handle.read_clip(0) == track["clips"][0], path
                checked["clips"] += 1
            if track["devices"]:
                device = handle.read_device(0)
                assert all(device[key] == value for key, value in track["devices"][0].items()), path
                checked["devices"] += 1
            lanes = handle.enumerate_automation()
            assert isinstance(lanes, list), path
            checked["lanes"] += len(lanes)
        for method, params in [
            ("track.read", {"path": "__openwig_missing_track__"}),
            ("device.read", {"path": "master", "device": -1}),
            ("clip.read", {"path": "master", "clip": -1}),
        ]:
            try:
                song.b.request(method, params)
            except BridgeError:
                pass
            else:
                raise AssertionError(f"invalid read accepted: {method}")
        after = export_to_dict(song, out / "reader-after.dawproject")
        assert before == after, "readers changed the exported document"
        report = parity_report(dump, after)
        values = assert_reader_values(dump, after)
        (out / "reader-value-checks.json").write_text(json.dumps(values, indent=2), encoding="utf-8")
        for name, data in (("reader-dump", dump), ("reader-parity", report), ("reader-checked", checked)):
            (out / (name + ".json")).write_text(json.dumps(data, indent=2), encoding="utf-8")
        assert not report["structure_mismatches"], report["structure_mismatches"]
        print(json.dumps({"checked": checked, "sections": report["sections"]}, indent=2))
    finally:
        bridge.stop()


if __name__ == "__main__":
    main()
