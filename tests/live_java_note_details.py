"""Manual MIDI conditions/expressions gate; clears only an acknowledged disposable project."""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture, tone_rms
from live_java_launcher import ensure_audio, wait_for_document


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def envelope_check(song, output, options, label):
    time.sleep(.4)
    rate, data = capture(song, output / f"{label}.wav", **options)
    # At 150 BPM point 2 is at .8 seconds, including for RAW audio.
    quiet = tone_rms(data, rate, .15, 440)
    loud = tone_rms(data, rate, 1.1, 440)
    write_json(output / f"{label}.json", dict(quiet=quiet, loud=loud, ratio=loud / quiet))
    assert loud > .005 and 13 < loud / quiet < 19, (quiet, loud)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--project", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--disposable", action="store_true")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--process-capture", type=Path)
    p.add_argument("--engine-pid", type=int)
    a = p.parse_args()
    if not a.verify and not a.disposable:
        p.error("--disposable acknowledges clearing the selected project")
    if bool(a.process_capture) != bool(a.engine_pid):
        p.error("supply both process-capture and engine-pid")
    target, out = a.project.resolve(), a.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    options = dict(capture_exe=a.process_capture, engine_pid=a.engine_pid)
    b = BridgeClient(port=7778); b.start()
    try:
        assert b.wait_connected(40)
        wait_for_document(b, target)
    finally:
        b.stop()
    with JavaSong(bars=3) as s:
        s.select_project(target)
        assert Path(s.project_info()["path"]) == target
        s.stop(); ensure_audio(s)
        if a.verify:
            from live_java_recreate import audit
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            write_json(out / "persistence.json", report)
            assert not report["differences"], report
            envelope_check(s, out, options, "reopened-envelope")
            return
        s.clear(); s.tempo(150)
        t = s.track("MIDI")
        t.clip([(60, 0, 4, .8)], dur=8)
        conditions = dict(chance=.6, chance_enabled=False, occurrence="NOT_FIRST",
                          occurrence_enabled=False, recurrence=42, recurrence_enabled=False,
                          repeat_count=-3, repeat_curve=.3, repeat_enabled=False)
        curves = {
            "gain": dict(unit="DECIBEL", points=[[0, -6, .3], [1, 0, 0, "hold"], [2, -12]]),
            "pan": dict(unit="RATIO", points=[[0, -.5], [3, .5, -.2]]),
            "transpose": dict(unit="SEMITONES", points=[[0, -2], [1, 7]]),
            "timbre": dict(unit="RATIO", points=[[0, .1], [2, .8]]),
            "pressure": dict(unit="RATIO", points=[[0, .2], [2, .7]]),
        }
        first = t.update_note(0, key=65, channel=3, on_velocity=.7, off_velocity=.3,
                              velocity_spread=.25, content_start=.25,
                              expressions=curves, **conditions)["data"]
        for name, value in conditions.items():
            assert first[name] == value, (name, first)
        assert first["recurrence_period"] == 3 and first["recurrence_iterations"] == [True, False, True]
        assert len(first["expressions"]) == 5
        second = t.update_note(0, key=66)["data"]
        assert second == {**first, "key": 66}, "unrelated fields/curves changed"
        # Copy-on-write independence for both arranger and launcher note content.
        copy = s.track("Copy")
        t.copy_clip(0, 0, destination=copy)
        copy.update_note(0, key=70)
        assert t.read_clip(0)["notes"][0] == second
        t.copy_arranger_to_launcher(0, 0)
        slot = t.update_note(slot=0, key=71)["data"]
        assert slot["key"] == 71 and t.read_clip(0)["notes"][0] == second
        added = t.import_note(0, data=dict(key=72, start=4, duration=2))
        assert added["note"] == 1
        assert t.delete_note(0, 1)["count"] == 1
        # Empty point lists clear a single curve, retaining the other four.
        cleared = copy.update_note(0, expressions={"gain": dict(unit="RATIO", points=[])})["data"]
        # expressions is a patch of named curves; unspecified expressions survive.
        assert not any(e["expression"] == "gain" and e["points"] for e in cleared["expressions"])
        assert len([e for e in cleared["expressions"] if e["points"]]) == 4
        for changes in [dict(key=128), dict(recurrence=2048), dict(repeat_count=128),
                        dict(occurrence="UNKNOWN"), dict(velocity_spread=2),
                        dict(expressions={"gain": dict(unit="RATIO", points=[[1, 1], [0, 0]])})]:
            before = t.read()
            try:
                s.b.request("note.update", t._track_params(clip=0, note=0, changes=changes))
            except BridgeError:
                pass
            else:
                raise AssertionError(f"accepted invalid change: {changes}")
            assert t.read() == before, changes
        rate = 48000
        sample = out / "tone.wav"
        with wave.open(str(sample), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
            w.writeframes((12000 * np.sin(2 * np.pi * 440 * np.arange(rate * 3) / rate)).astype("<i2").tobytes())
        audio = s.audio_track("Envelope")
        audio.import_audio(sample)
        event = audio.update_audio_event(0, expressions={
            "gain": dict(unit="DECIBEL", points=[[0, -24, 0, "hold"], [2, 0]])})["data"]
        assert event["expressions"][0]["time_unit"] == "beats"
        envelope_check(s, out, options, "raw-envelope")
        # Replacing a gain curve must not silently undo other expression curves.
        audio.update_audio_event(0, expressions={"pan": dict(unit="RATIO", points=[[0, 0], [2, 0]])})
        envelope_check(s, out, options, "patched-envelope")
        warped = s.audio_track("Warped expressions")
        audio.copy_clip(0, 0, destination=warped)
        warped.mute(True)
        changed = warped.update_audio_event(0, warp_mode="ELASTIQUE_PRO",
            warp_markers=[[0, 0], [7.5, 3]], chance=.7, chance_enabled=False,
            occurrence="FILL", occurrence_enabled=False, recurrence=42,
            recurrence_enabled=False, repeat_count=3, repeat_enabled=False,
            repeat_curve=-.3, expressions={
                "transpose": dict(unit="SEMITONES", points=[[0, 0], [4, 7, .2]]),
                "formant": dict(unit="SEMITONES", points=[[0, 2, 0, "hold"], [3, -1]])})["data"]
        assert changed["chance"] == .7 and changed["recurrence"] == 42
        assert {e["expression"] for e in changed["expressions"]} == {"gain", "pan", "transpose", "formant"}
        rejected = warped.read()
        try:
            s.b.request("audio_event.update", warped._track_params(clip=0, event=0,
                changes=dict(expressions={"pressure": dict(unit="RATIO", points=[[0, 0]])})))
        except BridgeError:
            pass
        else:
            raise AssertionError("accepted MIDI-only expression on audio")
        assert warped.read() == rejected
        write_json(out / "before.json", s.dump())
        write_json(out / "saved.json", s.save_project(out / "saved" / "project.bwproject"))


if __name__ == "__main__":
    main()
