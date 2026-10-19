"""Manual warp/pitch gate; --disposable clears only the explicitly open project.

Use --project <open file> --output <fresh scratch directory> --disposable
[--render]. Reopen output/saved/project.bwproject, then rerun with --verify.
"""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError


def measure(song, file, expected):
    time.sleep(1)
    song.render(file, tail=.3)
    with wave.open(str(file), "rb") as w:
        rate, channels = w.getframerate(), w.getnchannels()
        assert w.getsampwidth() == 2
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, channels).mean(axis=1) / 32768.
    peaks = []
    for start in (2.1, 3.1):
        segment = data[int(start * rate):int((start + .25) * rate)]
        spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
        peaks.append(float(np.fft.rfftfreq(len(segment), 1 / rate)[spectrum.argmax()]))
    report = dict(peak_hz=peaks, expected_hz=expected, rms=float(np.sqrt(np.mean(data ** 2))))
    file.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert all(abs(a - b) < 8 for a, b in zip(peaks, expected)), report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--live-render", action="store_true", help="also exercise the known immediate-playback gap")
    args = parser.parse_args()
    if not args.verify and not args.disposable:
        parser.error("--disposable acknowledges clearing the selected project")
    if args.render and not args.verify:
        parser.error("--render checks a reopened fixture with --verify; --live-render tests immediate playback")
    target, out = args.project.resolve(), args.output.resolve()
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
                measure(s, out / "reopened-pitch.wav", [440 * 2 ** (7 / 12), 880 * 2 ** (7 / 12)])
        else:
            s.clear()
            s.tempo(150)
            sample = out / "two-tones.wav"
            t = np.arange(144000) / 48000
            signal = (np.sin(2 * np.pi * np.where(t < 1, 440, 880) * t) * 20000).astype("<i2")
            with wave.open(str(sample), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(48000); w.writeframes(signal.tobytes())
            source = s.audio_track("Warp source")
            source.import_audio(sample, start=2)
            source.mute(True)
            original = source.read()["clips"][0]
            proof = s.audio_track("Warp proof")
            source.copy_clip(0, 2, destination=proof)
            markers = [[0, 0], [5, 1], [7.5, 3]]
            proof.update_audio_event(0, 0, warp_mode="ELASTIQUE_PRO", warp_markers=markers,
                                     content_offset=0, transpose=0, preserve_formants=False)
            if args.live_render:
                measure(s, out / "stretch.wav", [440, 880])
            proof.update_audio_event(0, 0, warp_mode="REPITCH")
            if args.live_render:
                measure(s, out / "repitch.wav", [220, 1760])
            settings = proof.update_audio_event(0, 0, warp_mode="ELASTIQUE_PRO", transpose=7,
                         formant=2, pan=-.5, content_offset=1.25, preserve_formants=True,
                         preview_onsets=True, preserve_transients_mode="HARD", transient_rate="QUARTER",
                         warp_grain_size="LARGE", warp_tail_mode="GRAIN")["data"]
            assert settings["sample_play_start_seconds"] == .25
            for key, value in dict(preserve_formants=True, preview_onsets=True, preserve_transients_mode="HARD",
                                   transient_rate="QUARTER", warp_grain_size="LARGE", warp_tail_mode="GRAIN").items():
                assert settings[key] == value
            expressions = {e["expression"]: e["points"][0]["value"] for e in settings["expressions"]}
            assert abs(expressions["transpose"] - 7) < 1e-6 and expressions["pan"] == -.5 and expressions["formant"] == 2
            before = proof.read()
            for changes in (dict(warp_markers=[[0, 0], [5, 99]]), dict(content_offset=99),
                            dict(warp_mode="RAW", transpose=7), dict(source_offset=.5)):
                try:
                    proof.update_audio_event(0, 0, **changes)
                except BridgeError:
                    pass
                else:
                    raise AssertionError("invalid warp request accepted")
                assert proof.read() == before
            modes = ["RAW", "STRETCH", "STRETCH_HD", "REPITCH", "SLICE", "CYCLIC",
                     "ELASTIQUE_PRO", "ELASTIQUE", "ELASTIQUE_ECO", "ELASTIQUE_SOLO"]
            for mode in modes:
                changes = dict(warp_mode=mode)
                if mode != "RAW": changes.update(warp_markers=markers, content_offset=0)
                assert proof.update_audio_event(0, 0, **changes)["data"]["warp_mode"] == mode
            proof.update_audio_event(0, 0, warp_mode="ELASTIQUE_PRO", transpose=7, formant=0,
                                     pan=0, content_offset=0, preserve_formants=False,
                                     preserve_transients_mode="OFF", transient_rate="ONSETS",
                                     warp_grain_size="NORMAL", warp_tail_mode="PINGPONG", preview_onsets=False)
            assert source.read()["clips"][0] == original
            settings_clip = proof.copy_clip(0, 20)["clip"]
            proof.update_audio_event(settings_clip, 0, content_offset=1.25, formant=2, pan=-.5,
                                     preserve_formants=True, preview_onsets=True,
                                     preserve_transients_mode="HARD", transient_rate="QUARTER",
                                     warp_grain_size="LARGE", warp_tail_mode="GRAIN")
            s.save_project(out / "saved/project.bwproject")
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
            if args.live_render:
                measure(s, out / "pitch.wav", [440 * 2 ** (7 / 12), 880 * 2 ** (7 / 12)])
        print("LIVE WARP/PITCH GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
