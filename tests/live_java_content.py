"""Manual native copy/split and expressive MIDI gate on a disposable project.

Run --disposable --project <open path> --output <fresh scratch directory>.
Reopen output/saved/project.bwproject, then rerun with --verify instead.
"""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np

from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if not args.verify and not args.disposable:
        parser.error("--disposable acknowledges clearing the selected project")
    target = args.project.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = BridgeClient(port=7778)
    b.start()
    try:
        assert b.wait_connected(40)
        deadline = time.monotonic() + 80
        while True:
            try:
                if any(Path(d["path"]) == target for d in b.request("document.list")["documents"]):
                    break
            except Exception:
                pass
            assert time.monotonic() < deadline, "requested project is not open"
            time.sleep(.25)
    finally:
        b.stop()
    with JavaSong() as s:
        s.select_project(target)
        assert Path(s.project_info()["path"]) == target
        s.stop()
        if args.verify:
            from live_java_recreate import audit
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
        else:
            s.clear()
            s.tempo(120)
            group = s.group_track("Content group")
            t = s.track("Source MIDI", device="Polysynth", parent=group)
            t.clip([(60, 0, 1, .7)], start=2, dur=4)
            t.update_clip(0, name="Expressive", loop=True, loop_start=.5,
                          loop_duration=3, play_start_offset=.25, color="#aa55ee")
            notes = [dict(key=64, channel=3, start=.125, duration=2.5,
                          on_velocity=.6, off_velocity=.2, muted=False, chance=.75,
                          expressions={
                              "pressure": dict(unit="PERCENTAGE_OF_RANGE", points=[[0, 20], [2.5, 80]]),
                              "timbre": dict(unit="PERCENTAGE_OF_RANGE", points=[[0, 30], [2.5, 60]]),
                              "transpose": dict(unit="SEMITONES", points=[[0, 0], [2.5, 7]]),
                              "gain": dict(unit="DECIBEL", points=[[0, -6], [2.5, 0]]),
                              "pan": dict(unit="RATIO", points=[[0, .25], [2.5, .75]]),
                          })]
            source = t.replace_notes(0, notes)
            note = source["notes"][0]
            assert note["channel"] == 3 and note["chance"] == .75
            curves = {e["expression"]: e["points"] for e in note["expressions"]}
            assert len(curves) == 5 and abs(curves["gain"][0]["value"] - 10 ** (-6 / 20)) < 1e-6
            assert abs(curves["pressure"][1]["value"] - .8) < 1e-6
            assert abs(curves["transpose"][1]["value"] - 7) < 1e-6
            copied = t.copy_clip(0, 8)
            assert copied["data"] == {**source, "start": 8}
            pieces = t.split_clip(copied["clip"], 10)["clips"]
            assert [(p["data"]["start"], p["data"]["duration"]) for p in pieces] == [(8, 2), (10, 2)]
            assert pieces[1]["data"]["play_start_offset"] == 2.25
            assert pieces[1]["data"]["notes"] == source["notes"]
            sibling = pieces[1]["data"]
            t.replace_notes(1, [dict(key=72, duration=1, muted=True)])
            t.replace_notes(1, [])
            assert not t.read()["clips"][1]["notes"]
            assert t.read()["clips"][0] == source and t.read()["clips"][2] == sibling
            t.replace_notes(1, notes)
            before = t.read()
            invalid = [dict(key=64, duration=1, expressions={"pressure": dict(unit="BAD_UNIT", points=[[0, .5]])})]
            try:
                t.replace_notes(1, notes + invalid)
            except BridgeError:
                pass
            else:
                raise AssertionError("unknown unit accepted")
            assert t.read() == before, "rejected replacement mutated content"
            for beat in (8, 10, 12):
                try:
                    t.split_clip(1, beat)
                except BridgeError:
                    pass
                else:
                    raise AssertionError("boundary/outside split accepted")
            destination = s.track("Cross-track MIDI", parent=group)
            cross = t.copy_clip(0, 0, destination=destination)
            assert cross["data"] == {**source, "start": 0}
            destination.replace_notes(cross["clip"], [])
            assert t.read()["clips"][0] == source
            audio = s.audio_track("Source Audio", parent=group)
            sample = out / "synthetic.wav"
            a = (np.sin(2 * np.pi * 440 * np.arange(96000) / 48000) * 5000).astype("<i2")
            with wave.open(str(sample), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(48000)
                w.writeframes(a.tobytes())
            audio.import_audio(sample, start=2, source_offset=.5, gain_db=-6)
            original = audio.update_clip(0, duration=2.5, fade_in=.25, fade_out=.5)
            copied = audio.copy_clip(0, 8)
            assert copied["data"] == {**original, "start": 8}
            pieces = audio.split_clip(copied["clip"], 9)["clips"]
            assert [(p["data"]["start"], p["data"]["duration"]) for p in pieces] == [(8, 1), (9, 1.5)]
            assert pieces[0]["data"]["fade_out"] == 0 and pieces[1]["data"]["fade_in"] == 0
            assert pieces[1]["data"]["play_start_offset"] == 1
            assert audio.read()["clips"][0] == original
            s.save_project(out / "saved/project.bwproject")
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
        print("LIVE COPY/SPLIT/MIDI CONTENT GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
