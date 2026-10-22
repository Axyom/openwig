"""Manual native arranger/automation gate; clears only an acknowledged project.

PYTHONPATH=src python tests/live_java_ranges.py --disposable --project scratch/probe-project/probe.bwproject
"""
import argparse
from pathlib import Path
import time
from openwig import JavaSong
from openwig.bridge import BridgeClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("--disposable acknowledges clearing the selected project")
    path = args.project.resolve()
    b = BridgeClient(port=7778)
    b.start()
    try:
        assert b.wait_connected(40)
        deadline = time.monotonic() + 80
        while True:
            try:
                if any(Path(d["path"]) == path for d in b.request("document.list")["documents"]):
                    break
            except Exception:
                pass  # The handshake may precede application event-queue readiness.
            assert time.monotonic() < deadline, "requested disposable project is not open"
            time.sleep(.25)
    finally:
        b.stop()
    with JavaSong() as s:
        s.select_project(path)
        assert Path(s.project_info()["path"]) == path
        s.stop()
        s.clear()
        group = s.group_track("Range group")
        t = s.track("Crossing", device="Polysynth", parent=group)
        t.clip([(60, 0, 6, .5)], start=2, dur=6)
        t.clip([(67, 0, 4, .5)], start=12, dur=4)
        t.automate("volume", [(8, .25, .3, "hold"), (16, .75, -.2, "linear")])
        lane = t.automation("volume")["points"]
        assert len(lane) == 3 and lane[0]["beat"] == 0, lane
        assert lane[1]["beat"] == 8 and abs(lane[1]["normalized"] - .25) < 1e-6
        assert lane[1]["interpolation"] == "hold" and lane[1]["curvature"] == 0
        assert lane[2]["beat"] == 16 and abs(lane[2]["normalized"] - .75) < 1e-6
        assert abs(lane[2]["curvature"] + .2) < 1e-6
        master = s.track_at("master")
        master.automate("volume", [(0, .8), (12, .7)])
        s.effect_track("Range Return")
        fx = s.track_at("fx/Range Return")
        fx.automate("volume", [(0, .6), (12, .5)])
        s.add_cue_marker(12, "Range end")
        print("INSERT", s.insert_time(4, 4))
        clips = [(c["start"], c["duration"]) for c in t.read()["clips"]]
        assert clips == [(2, 2), (8, 4), (16, 4)], clips
        assert [p["beat"] for p in t.automation("volume")["points"]][-2:] == [12, 20]
        assert master.automation("volume")["points"][-1]["beat"] == 16
        assert fx.automation("volume")["points"][-1]["beat"] == 16
        assert any(m["name"] == "Range end" and m["time"] == 16 for m in s.dump()["cue_markers"])
        print("DELETE", s.delete_range(4, 8))
        clips = [(c["start"], c["duration"]) for c in t.read()["clips"]]
        assert clips == [(2, 2), (4, 4), (12, 4)], clips
        assert [p["beat"] for p in t.automation("volume")["points"]][-2:] == [8, 16]
        assert master.automation("volume")["points"][-1]["beat"] == 12
        assert fx.automation("volume")["points"][-1]["beat"] == 12
        assert any(m["name"] == "Range end" and m["time"] == 12 for m in s.dump()["cue_markers"])
        print("LIVE RANGE/IMPLICIT-ANCHOR GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
