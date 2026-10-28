"""Manual warped reverse/offset/resolution gate on an explicitly disposable project."""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture
from live_java_launcher import ensure_audio, wait_for_document


def measure(song, file, expected, capture_options):
    time.sleep(.5)
    rate, data = capture(song, file, **capture_options)
    base = song.b.request("transport.beats_to_seconds", {"beats": 2.})["seconds"]
    peaks = []
    for local, frequency in expected:
        segment = data[int((base + local) * rate):int((base + local + .1) * rate)]
        spectrum = abs(np.fft.rfft(segment * np.hanning(len(segment))))
        peak = float(np.fft.rfftfreq(len(segment), 1 / rate)[spectrum.argmax()])
        rms = float(np.sqrt(np.mean(segment ** 2)))
        peaks.append(dict(local=local, peak_hz=peak, expected_hz=frequency, rms=rms))
    file.with_suffix(".json").write_text(json.dumps(peaks, indent=2), encoding="utf-8")
    assert all(abs(p["peak_hz"] - p["expected_hz"]) < 12 and p["rms"] > .001 for p in peaks), peaks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--process-capture", type=Path)
    parser.add_argument("--engine-pid", type=int)
    args = parser.parse_args()
    if not args.verify and not args.disposable:
        parser.error("--disposable acknowledges clearing the selected project")
    if bool(args.process_capture) != bool(args.engine_pid):
        parser.error("supply both process-capture and engine-pid")
    target, out = args.project.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    options = dict(capture_exe=args.process_capture, engine_pid=args.engine_pid)
    b = BridgeClient(port=7778)
    b.start()
    try:
        assert b.wait_connected(40)
        wait_for_document(b, target)
    finally:
        b.stop()
    with JavaSong(bars=4) as s:
        s.select_project(target)
        assert Path(s.project_info()["path"]) == target
        s.stop()
        ensure_audio(s)
        if args.verify:
            from live_java_recreate import audit
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
            measure(s, out / "reopened-reverse-offset.wav", [(.04, 1320), (.4, 880), (1.2, 440)], options)
        else:
            s.clear()
            s.tempo(150)
            rate = 48000
            t = np.arange(rate * 3) / rate
            signal = (16000 * np.sin(2 * np.pi * np.select([t < 1, t < 2], [440, 880], default=1320) * t)).astype("<i2")
            sample = out / "three-tones.wav"
            with wave.open(str(sample), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(signal.tobytes())
            source = s.audio_track("Source")
            source.import_audio(sample, start=2)
            source.mute(True)
            original = source.read()
            proof = s.audio_track("Proof")
            source.copy_clip(0, 2, destination=proof)
            proof.update_audio_event(0, 0, duration=6, warp_mode="ELASTIQUE_PRO",
                                     warp_markers=[[0, 0], [4, 1], [6, 3]],
                                     content_offset=0, formant_envelope_resolution=256)
            forward = proof.read()
            measure(s, out / "forward.wav", [(.2, 440), (1.8, 880), (2.2, 1320)], options)
            reverse = proof.update_audio_event(0, 0, reverse=True)["data"]
            assert reverse["reverse"] and reverse["sample_play_start_seconds"] == 3
            assert reverse["sample_play_stop_seconds"] == 0
            assert reverse["warp_markers"][0]["content_time"] == 3
            measure(s, out / "reverse.wav", [(.2, 1320), (.6, 880), (1.2, 440)], options)
            proof.update_audio_event(0, 0, reverse=False)
            assert proof.read() == forward, "reverse twice must restore the forward snapshot"
            offset = proof.update_audio_event(0, 0, source_offset=.5)["data"]
            assert offset["sample_play_start_seconds"] == .5 and offset["content_start"] == 2
            measure(s, out / "forward-offset.wav", [(1.0, 880), (1.4, 1320)], options)
            proof.update_audio_event(0, 0, content_offset=0)
            proof.update_audio_event(0, 0, reverse=True)
            reverse_offset = proof.update_audio_event(0, 0, source_offset=2.5)["data"]
            assert reverse_offset["sample_play_start_seconds"] == 2.5
            assert reverse_offset["sample_play_stop_seconds"] == 0
            assert reverse_offset["content_start"] == .5
            measure(s, out / "reverse-offset.wav", [(.04, 1320), (.4, 880), (1.2, 440)], options)
            before = proof.read()
            for changes in (dict(source_offset=0), dict(source_offset=4),
                            dict(source_offset=1, content_offset=1), dict(source_offset=1, reverse=False),
                            dict(warp_markers=[[0, 0], [6, 3]]), dict(formant_envelope_resolution=513)):
                try:
                    proof.update_audio_event(0, 0, **changes)
                except (ValueError, BridgeError):
                    pass
                else:
                    raise AssertionError(("invalid edit accepted", changes))
                assert proof.read() == before
            assert source.read() == original
            slot = s.audio_track("Slot").mute(True)
            slot.import_launcher_audio(0, sample)
            slot.update_audio_event(index=0, slot=0, warp_mode="REPITCH", warp_markers=[[0, 0], [6, 3]],
                                   content_offset=0, formant_envelope_resolution=8)
            slot.update_audio_event(index=0, slot=0, reverse=True)
            assert slot.update_audio_event(index=0, slot=0, source_offset=2.75)["data"]["sample_play_start_seconds"] == 2.75
            s.save_project(out / "saved/project.bwproject")
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
        print("LIVE WARPED AUDIO DETAILS GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
