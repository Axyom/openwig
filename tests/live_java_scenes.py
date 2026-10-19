"""Manual scene-bank structure gate with nested/effect slots and audible relaunch."""
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
from live_java_recreate import audit


def row_names(track):
    return [slot["clip"]["name"] if slot["clip"] else None
            for slot in track.read()["clip_slots"]]


def measure(s, out, options, label, expected=None, absent=None, launch=None, track=None):
    if track is not None:
        # Relaunching a playing/queued slot, or launching right after a stop,
        # can leave it silent; start every launched capture from rest.
        track.stop_launcher(); track.return_to_arranger(); s.stop()
        time.sleep(.5)
    rate, data = capture(s, out / f"{label}.wav", on_play=launch, **options)
    levels = {str(f): tone_rms(data, rate, 1., f) for f in (440, 880)}
    (out / f"{label}.json").write_text(json.dumps(levels), encoding="utf-8")
    if expected:
        assert levels[str(expected)] > .005, levels
    if absent:
        assert levels[str(absent)] < .001, levels


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--project", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--disposable", action="store_true")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--process-capture", type=Path, required=True)
    p.add_argument("--engine-pid", type=int, required=True)
    a = p.parse_args()
    if not a.verify and not a.disposable:
        p.error("--disposable acknowledges clearing this project")
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
            report = audit(json.loads((out / "before.json").read_text(encoding="utf-8")), s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
            assert [r["name"] for r in s.scenes()] == ["A", "B"]
            audio = s.track_at("Group/Audio")
            measure(s, out, options, "reopened-launch", 440, 880, launch=lambda: audio.launch_slot(0), track=audio)
            audio.stop_launcher(); audio.return_to_arranger()
            return
        s.clear(); s.tempo(150)
        group = s.group_track("Group")
        audio = s.audio_track("Audio", parent=group)
        midi = s.track("MIDI", parent=group)
        s.effect_track("Scene FX")
        fx = s.track_at("fx/Scene FX").mute()
        # A completely empty bank must stay empty when read; all track banks
        # must also be resized, including group children and effect tracks.
        for index in reversed(range(len(s.scenes()))):
            s.delete_scene(index)
        assert s.scenes() == []
        for track in [group, audio, midi, fx]:
            assert track.read()["clip_slots"] == []
        for name in ["A", "B", "C"]:
            result = s.insert_scene()
            s.update_scene(result["index"], name=name, comment=f"Row {name}", color=(.2, .6, .3))
        for index, frequency in [(0, 440), (2, 880)]:
            file = out / f"tone-{frequency}.wav"; rate = 48000
            with wave.open(str(file), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
                w.writeframes((12000 * np.sin(2 * np.pi * frequency * np.arange(int(rate * 1.6)) / rate)).astype("<i2").tobytes())
            audio.import_launcher_audio(index, file, name=f"Audio {frequency}", loop=True)
            audio.update_launch_settings(index, launch_quantization="OFF")
        for index, name in enumerate(["A", "B", "C"]):
            midi.put_launcher_notes(index, [dict(key=60 + index, duration=4)], 4, name=f"MIDI {name}")
        fx.import_launcher_audio(2, out / "tone-440.wav", name="FX C")
        midi.clear_launcher_slot(1, has_stop=False)
        s.insert_scene(1); s.update_scene(1, name="Inserted")
        assert [r["name"] for r in s.scenes()] == ["A", "Inserted", "B", "C"]
        assert row_names(audio) == ["Audio 440", None, None, "Audio 880"]
        assert row_names(midi) == ["MIDI A", None, None, "MIDI C"]
        assert not midi.read_launcher_slot(2)["has_stop"]
        assert row_names(fx) == [None, None, None, "FX C"]
        s.move_scene(3, 0)
        assert [r["name"] for r in s.scenes()] == ["C", "A", "Inserted", "B"]
        assert row_names(audio) == ["Audio 880", "Audio 440", None, None]
        assert row_names(midi) == ["MIDI C", "MIDI A", None, None]
        assert row_names(fx) == ["FX C", None, None, None]
        assert not midi.read_launcher_slot(3)["has_stop"]
        s.delete_scene(2)
        assert [r["name"] for r in s.scenes()] == ["C", "A", "B"]
        measure(s, out, options, "moved-launch", 880, 440, launch=lambda: audio.launch_slot(0), track=audio)
        audio.launch_slot(0)
        time.sleep(.1)
        assert audio.read_launcher_slot(0)["playing"]
        s.delete_scene(0)
        measure(s, out, options, "deleted-playing-row", absent=880)
        assert [r["name"] for r in s.scenes()] == ["A", "B"]
        assert row_names(audio) == ["Audio 440", None]
        assert row_names(midi) == ["MIDI A", None]
        assert row_names(fx) == [None, None]
        assert not midi.read_launcher_slot(1)["has_stop"]
        measure(s, out, options, "remaining-launch", 440, 880, launch=lambda: audio.launch_slot(0), track=audio)
        audio.stop_launcher(); audio.return_to_arranger(); s.stop()
        before = s.dump()
        for method, params in [
            ("scene.insert", dict(index=99)), ("scene.delete", dict(index=-1)),
            ("scene.move", dict(index=0, destination=99)), ("scene.move", dict(index=.5, destination=0)),
            ("scene.update", dict(index=99, name="must not grow")),
            ("scene.update", dict(index=0, name="must not change", color="invalid")),
            ("scene.update", dict(index=0, name="must not change", r=2, g=0, b=0)),
        ]:
            try:
                s.b.request(method, params)
            except BridgeError:
                pass
            else:
                raise AssertionError(f"invalid scene operation accepted: {method} {params}")
            assert s.dump() == before
        (out / "before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        s.save_project(out / "saved" / "project.bwproject")


if __name__ == "__main__":
    main()
