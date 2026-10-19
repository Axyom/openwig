"""Manual warp/pitch gate; --disposable clears only the explicitly open project.

Use --project <open file> --output <fresh scratch directory> --disposable
[--render]. Reopen output/saved/project.bwproject, then rerun with --verify.
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture, tone_rms


def measure(song, file, expected, **capture_options):
    time.sleep(1)
    rate, data = capture(song, file, **capture_options)
    peaks = []
    for start in (2.1, 3.1):
        segment = data[int(start * rate):int((start + .25) * rate)]
        spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
        peaks.append(float(np.fft.rfftfreq(len(segment), 1 / rate)[spectrum.argmax()]))
    report = dict(peak_hz=peaks, expected_hz=expected, rms=float(np.sqrt(np.mean(data ** 2))))
    file.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert all(abs(a - b) < 8 for a, b in zip(peaks, expected)), report


def assert_cleared_audio(song, out, capture_options):
    # Metronome clicks can remain after clear. Reject the fixture tones,
    # rather than requiring the whole audio endpoint to be silent.
    song.clear()
    rate, data = capture(song, out / "cleared.wav", **capture_options)
    levels = [tone_rms(data, rate, start, frequency)
              for start in (1.1, 1.6, 2.1, 2.6, 3.1, 3.5)
              for frequency in (220, 440, 659.255, 880, 1318.51, 1760)]
    report = dict(max_fixture_tone_rms=max(levels), limit=.001,
                  visible_main_tracks=song.b.request("track.count")["count"],
                  visible_effect_tracks=song.b.request("fxtrack.count")["count"])
    assert report["visible_main_tracks"] == report["visible_effect_tracks"] == 0
    (out / "cleared.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert max(levels) < .001, report


def author(s, out, live_render, capture_options):
    import wave
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
    group = s.group_track("Warp group")
    proof = s.audio_track("Warp proof", parent=group)
    source.copy_clip(0, 2, destination=proof)
    markers = [[0, 0], [5, 1], [7.5, 3]]
    proof.update_audio_event(0, 0, warp_mode="ELASTIQUE_PRO", warp_markers=markers,
                             content_offset=0, transpose=0, preserve_formants=False)
    if live_render:
        measure(s, out / "stretch.wav", [440, 880], **capture_options)
    proof.update_audio_event(0, 0, warp_mode="REPITCH")
    if live_render:
        measure(s, out / "repitch.wav", [220, 1760], **capture_options)
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
    if live_render:
        measure(s, out / "pitch.wav", [440 * 2 ** (7 / 12), 880 * 2 ** (7 / 12)], **capture_options)
    if live_render:
        assert_cleared_audio(s, out, capture_options)
        # FX tracks also have separate visible and owning banks.
        s.effect_track("Deletion FX")
        effect = s.track_at("fx/Deletion FX")
        effect.import_audio(sample, start=2)
        measure(s, out / "effect-before-delete.wav", [880, 880], **capture_options)
        s.b.request("fxtrack.delete", {"index": 0})
        assert s.b.request("fxtrack.count")["count"] == 0
        fx_out = out / "effect-after-delete"
        fx_out.mkdir()
        assert_cleared_audio(s, fx_out, capture_options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--live-render", action="store_true", help="check immediate processing and absence of deleted-track audio")
    parser.add_argument("--repeat", type=int, default=1, help="author fresh fixtures repeatedly in one host")
    parser.add_argument("--process-capture", type=Path, help="optional compiled Windows process-loopback helper")
    parser.add_argument("--engine-pid", type=int, help="PID of the disposable Bitwig audio engine")
    args = parser.parse_args()
    if args.repeat < 1 or (args.verify and args.repeat != 1):
        parser.error("--repeat must be positive and is for authoring only")
    if bool(args.process_capture) != bool(args.engine_pid):
        parser.error("--process-capture and --engine-pid must be supplied together")
    capture_options = dict(capture_exe=args.process_capture, engine_pid=args.engine_pid)
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
        from live_java_launcher import ensure_audio
        ensure_audio(s)
        if args.verify:
            from live_java_recreate import audit
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
            if args.render:
                measure(s, out / "reopened-pitch.wav", [440 * 2 ** (7 / 12), 880 * 2 ** (7 / 12)], **capture_options)
        else:
            for run in range(args.repeat):
                fixture = out if args.repeat == 1 else out / f"run-{run + 1}"
                fixture.mkdir(parents=True, exist_ok=True)
                author(s, fixture, args.live_render, capture_options)
        print("LIVE WARP/PITCH GATE PASSED", flush=True)


if __name__ == "__main__":
    main()
