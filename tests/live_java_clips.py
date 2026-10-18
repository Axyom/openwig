"""Manual clip gate. Requires --disposable and an explicitly open project.

Run with --project PATH --output scratch/clip-gate --disposable, then reopen
the saved project and rerun with --verify (without --disposable).
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
    parser.add_argument("--render", action="store_true")
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
    with JavaSong(bars=2) as s:
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
            t = s.track("Edit MIDI", device="Polysynth")
            t.clip([(60, 0, 1, .7)], start=0, dur=4)
            t.clip([(67, 0, 1, .5)], start=8, dur=4)
            t.update_clip(0, start=16, duration=2, name="Moved", muted=True,
                          loop=True, loop_start=.5, loop_duration=1.5,
                          play_start_offset=.25, color="#aa55ee")
            clips = t.read()["clips"]
            assert [c["start"] for c in clips] == [8, 16]
            assert clips[1]["notes"][0]["key"] == 60 and clips[1]["muted"]
            before = t.read()
            for changes in [dict(start=9), dict(fade_in=.1), dict(duration=-1)]:
                try:
                    t.update_clip(1, **changes)
                except (BridgeError, ValueError):
                    pass
                else:
                    raise AssertionError(f"invalid edit accepted: {changes}")
                assert t.read() == before, "invalid edit mutated the track"
            t.delete_clip(0)
            assert [c["start"] for c in t.read()["clips"]] == [16]
            source = out / "synthetic.wav"
            a = (np.sin(2 * np.pi * 440 * np.arange(96000) / 48000) * 5000).astype("<i2")
            with wave.open(str(source), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(48000)
                w.writeframes(a.tobytes())
            audio = s.audio_track("Edit Audio")
            raw = audio.import_audio(source, source_offset=.5)
            assert abs(raw["data"]["duration"] - 3) < 1e-6
            assert raw["data"]["audio_events"][0]["warp_mode"] == "RAW"
            audio.delete_clip(raw["clip"])
            imported = audio.import_audio(source, start=4, source_offset=.5, gain_db=-6)
            event = imported["data"]["audio_events"][0]
            assert event["warp_mode"] == "RAW"
            assert abs(event["sample_play_start_seconds"] - .5) < 1e-6
            gain = next(e for e in event["expressions"] if e["expression"] == "gain")
            assert abs(gain["points"][0]["value"] - 10 ** (-6 / 20)) < 1e-6
            audio.update_clip(imported["clip"], duration=2.5, fade_in=.25,
                              fade_out=.5, name="Trimmed source")
            s.save_project(out / "saved/project.bwproject")
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
        if args.render:
            recording = out / ("reopened.wav" if args.verify else "playback.wav")
            s.render(recording, tail=.5)
            with wave.open(str(recording), "rb") as w:
                rate, channels = w.getframerate(), w.getnchannels()
                assert w.getsampwidth() == 2
                a = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, channels) / 32768.
            segment = a[int(2.15 * rate):int(2.55 * rate)].mean(axis=1)
            spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
            frequencies = np.fft.rfftfreq(len(segment), 1 / rate)
            band = (frequencies > 430) & (frequencies < 450)
            peak = float(frequencies[band][spectrum[band].argmax()])
            tone_amplitude = float(spectrum[band].max() * 4 / len(segment))
            report = dict(tone_amplitude=tone_amplitude, rms=float(np.sqrt(np.mean(a * a))), peak_hz=peak,
                          expected_hz=440)
            recording.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert report["rms"] > .001, report
            assert tone_amplitude > .001, report
            assert abs(peak - 440) < 5, report
        print("LIVE CLIP GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
