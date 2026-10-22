"""Manual RAW audio event gate: disposable mutations, optional audible reverse.

Run --disposable --project <open file> --output <fresh scratch directory>
[--render]. Reopen output/saved/project.bwproject; rerun with --verify instead.
"""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np

from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError


def measure(song, path, expected):
    # Document commits precede engine/sample-cache updates. Give the synthetic
    # source time to settle before using a recording as DSP evidence.
    time.sleep(1)
    song.render(path, tail=.3)
    with wave.open(str(path), "rb") as w:
        rate, channels = w.getframerate(), w.getnchannels()
        assert w.getsampwidth() == 2
        samples = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, channels).mean(axis=1) / 32768.
    peaks = []
    base = song.b.request("transport.beats_to_seconds", {"beats": 2.})["seconds"]
    for start in (base + .2, base + 1.5):
        segment = samples[int(start * rate):int((start + .25) * rate)]
        spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
        hz = np.fft.rfftfreq(len(segment), 1 / rate)
        peaks.append(float(hz[spectrum.argmax()]))
    report = dict(peak_hz=peaks, expected_hz=expected, rms=float(np.sqrt(np.mean(samples ** 2))))
    path.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert all(abs(a - b) < 5 for a, b in zip(peaks, expected)), report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--tempo", type=float, default=120)
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
    with JavaSong(bars=4) as s:
        s.select_project(target)
        assert Path(s.project_info()["path"]) == target
        s.stop()
        if args.verify:
            from live_java_recreate import audit
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
            if args.render:
                measure(s, out / "reopened-reverse.wav", [880, 440])
        else:
            s.clear()
            s.tempo(args.tempo)
            sample = out / "two-tones.wav"
            t = np.arange(96000) / 48000
            frequencies = np.where(t < 1, 440, 880)
            signal = (np.sin(2 * np.pi * frequencies * t) * 16000).astype("<i2")
            with wave.open(str(sample), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(48000)
                w.writeframes(signal.tobytes())
            group = s.group_track("Audio events")
            source = s.audio_track("Edit", parent=group)
            source.import_audio(sample, start=2)
            source.mute(True)
            proof = s.audio_track("Reverse proof", parent=group)
            original = source.read()["clips"][0]
            source.copy_clip(0, 2, destination=proof)
            if args.render:
                measure(s, out / "forward.wav", [440, 880])
            result = proof.update_audio_event(0, 0, reverse=True)["data"]
            assert result["reverse"] and result["sample_play_start_seconds"] == 2 and result["sample_play_stop_seconds"] == 0
            if args.render:
                measure(s, out / "reverse.wav", [880, 440])
            assert source.read()["clips"][0] == original, "reverse modified source copy"
            reversed_clip = proof.read()["clips"][0]
            source.update_clip(0, duration=8)
            moved = source.update_audio_event(0, 0, start=4, duration=3, source_offset=.25,
                                               gain_db=-3, muted=True, fade_in=.2, fade_out=.3)["data"]
            assert moved["start"] == 4 and moved["duration"] == 3 and moved["muted"]
            assert abs(moved["sample_play_start_seconds"] - .25) < 1e-6
            assert abs(moved["fade_in"] - .2) < 1e-6 and abs(moved["fade_out"] - .3) < 1e-6
            gain = next(e for e in moved["expressions"] if e["expression"] == "gain")
            assert abs(gain["points"][0]["value"] - 10 ** (-3 / 20)) < 1e-6
            source.import_audio_event(0, sample, start=0, duration=2, source_offset=1, gain_db=-6)
            events = source.read()["clips"][0]["audio_events"]
            assert [(e["start"], e["duration"]) for e in events] == [(0, 2), (4, 3)]
            before = source.read()
            for action in (lambda: source.update_audio_event(0, 1, start=1),
                           lambda: source.update_audio_event(0, 1, source_offset=99),
                           lambda: source.import_audio_event(0, sample, start=1),
                           lambda: source.import_audio_event(0, sample, start=7, source_offset=99)):
                try:
                    action()
                except BridgeError:
                    pass
                else:
                    raise AssertionError("invalid audio event edit accepted")
                assert source.read() == before, "rejected edit mutated content"
            source.delete_audio_event(0, 0)
            assert source.read()["clips"][0]["audio_events"] == events[1:]
            source.import_audio_event(0, sample, start=0, duration=2, source_offset=1, gain_db=-6)
            assert proof.read()["clips"][0] == reversed_clip
            empty = proof.copy_clip(0, 16)["clip"]
            proof.update_audio_event(empty, 0, reverse=False)
            proof.update_audio_event(empty, 0, duration=2, source_offset=.25)
            partial = proof.update_audio_event(empty, 0, reverse=True)["data"]
            bpm = s.b.request("transport.get_tempo")["bpm"]
            assert abs(partial["sample_play_start_seconds"] - (.25 + 2 * 60 / bpm)) < 1e-6
            assert abs(partial["sample_play_stop_seconds"] - .25) < 1e-6
            assert proof.delete_audio_event(empty, 0)["count"] == 0
            assert proof.read()["clips"][empty]["audio_events"] == []
            assert proof.import_audio_event(empty, sample)["event"] == 0
            assert proof.read()["clips"][0] == reversed_clip
            proof.delete_clip(empty)
            s.save_project(out / "saved/project.bwproject")
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
        print("LIVE AUDIO EVENT GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
