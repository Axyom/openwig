"""Manual launcher gate: slot authoring, launch settings, playback, persistence.

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


def playable_tone(path):
    t = np.arange(96000) / 48000
    signal = (np.sin(2 * np.pi * 440 * t) * 16000).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(48000)
        w.writeframes(signal.tobytes())


def audible_launch(song, path):
    """Render while a launched slot loops; assert an audible 440 Hz tone."""
    time.sleep(1)
    song.render(path, tail=.3)
    with wave.open(str(path), "rb") as w:
        rate = w.getframerate()
        samples = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, w.getnchannels()).mean(axis=1) / 32768.
    segment = samples[:rate]
    spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment))))
    peak = float(np.fft.rfftfreq(len(segment), 1 / rate)[spectrum.argmax()])
    rms = float(np.sqrt(np.mean(samples ** 2)))
    path.with_suffix(".json").write_text(json.dumps(dict(peak_hz=peak, expected_hz=440., rms=rms), indent=2), encoding="utf-8")
    assert rms > 1e-3 and abs(peak - 440) < 8, (rms, peak)


def wait_for_document(bridge, target, timeout=80):
    deadline = time.monotonic() + timeout
    while True:
        try:
            if any(Path(d["path"]) == target for d in bridge.request("document.list")["documents"]):
                return
        except Exception:
            pass
        assert time.monotonic() < deadline, "requested project is not open"
        time.sleep(.25)


def ensure_audio(song, timeout=60):
    """Connect the audio engine; headless launches start without it.

    Slots refuse to launch and transport cannot play while the engine is
    disconnected (tracks stay engine-inactive). Activation is idempotent.
    """
    status = song.b.request("document.audio_status")
    if not status.get("ready"):
        song.b.request("document.activate_audio")
    deadline = time.monotonic() + timeout
    while not (status.get("connected") and status.get("ready")):
        assert time.monotonic() < deadline, f"audio engine not ready: {status}"
        time.sleep(.5)
        status = song.b.request("document.audio_status")
    return status


def author(args):
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = BridgeClient(port=7778)
    b.start()
    try:
        assert b.wait_connected(40)
        wait_for_document(b, args.project.resolve())
    finally:
        b.stop()
    with JavaSong(bars=4) as s:
        s.select_project(args.project.resolve())
        assert Path(s.project_info()["path"]) == args.project.resolve()
        s.stop()
        s.clear()
        s.tempo(args.tempo)
        tone = out / "tone.wav"
        playable_tone(tone)
        group = s.group_track("Launcher")
        midi = s.track("MIDI", kind="instrument", parent=group)
        audio = s.audio_track("Audio", parent=group)

        notes = [dict(key=60, duration=4), dict(key=64, start=1, duration=2, on_velocity=.9)]
        slot = midi.put_launcher_notes(0, notes, 4, name="Chord")
        assert slot["clip"]["name"] == "Chord" and slot["clip"]["duration"] == 4, slot
        assert len(slot["clip"]["notes"]) == 2 and not slot["playing"] and not slot["queued"], slot
        try:
            midi.put_launcher_notes(0, notes, 4)
            raise AssertionError("occupied slot accepted without replace")
        except BridgeError:
            pass
        assert midi.read_launcher_slot(0)["clip"]["name"] == "Chord", "rejected write mutated the slot"

        settings = midi.update_launch_settings(0, launch_quantization="BAR", launch_mode="SYNCED",
                                               next_action="PLAY_NEXT", next_action_time=2,
                                               use_loop_start_as_quantization_reference=True)
        written = settings["clip"]["launch_settings"]
        assert written["launch_quantization"] == "BAR" and written["launch_mode"] == "SYNCED", written
        assert written["next_action"] == "PLAY_NEXT" and written["next_action_time"] == 2, written
        assert written["use_loop_start_as_quantization_reference"] is True, written
        dumped = midi.read()["clip_slots"][0]["clip"]["launch_settings"]
        assert dumped == written, (dumped, written)

        slot = audio.import_launcher_audio(0, tone, name="Tone")
        assert slot["clip"]["name"] == "Tone" and abs(slot["clip"]["duration"] - 4) < 1e-6, slot
        audio.update_launch_settings(0, launch_quantization="OFF")

        audio.import_audio(tone, start=0)
        audio.update_clip(0, muted=True)
        copied = audio.copy_arranger_to_launcher(1, clip=0)
        assert copied["clip"] is not None and abs(copied["clip"]["duration"] - 4) < 1e-6, copied
        audio.update_launch_settings(1, launch_quantization="OFF")
        assert audio.read()["clips"][0]["muted"] is True, "slot copy mutated the arranger source"

        moved = audio.update_audio_event(None, 0, slot=0, gain_db=-6)["data"]
        gain = next(e for e in moved["expressions"] if e["expression"] == "gain")
        assert abs(gain["points"][0]["value"] - 10 ** (-6 / 20)) < 1e-6, moved
        resized = audio.update_launcher_clip(0, duration=8, fade_in=.1)
        assert resized["duration"] == 8 and abs(resized["fade_in"] - .1) < 1e-6, resized

        audio.copy_arranger_to_launcher(2, clip=0)
        cleared = audio.clear_launcher_slot(2, has_stop=False)
        assert cleared["clip"] is None and cleared["has_stop"] is False, cleared
        restored = audio.clear_launcher_slot(2, has_stop=True)
        assert restored["clip"] is None and restored["has_stop"] is True, restored
        deleted = audio.delete_launcher_clip(1)
        assert deleted["slot"] == 1 and deleted["clip"] is None and deleted["has_stop"], deleted

        try:
            audio.read_launcher_slot(999)
            raise AssertionError("out-of-range slot accepted")
        except BridgeError as error:
            assert "scene bank" in str(error), error

        ensure_audio(s)
        audio.launch_slot(0)
        time.sleep(1.5)
        assert audio.read_launcher_slot(0)["playing"] is True, "slot did not start"
        if args.render:
            audible_launch(s, out / "launch.wav")
        audio.stop_launcher()
        time.sleep(1)
        assert audio.read_launcher_slot(0)["playing"] is False, "slot did not stop"

        audio.launch_slot(0)
        audio.return_to_arranger()
        time.sleep(1)
        assert audio.read_launcher_slot(0)["playing"] is False, "return to arrangement left the slot playing"

        before = s.dump()
        (out / "before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        s.save_project(out / "saved" / "project.bwproject")
        if args.render:
            audible = audio.read_launcher_slot(0)
            assert audible["clip"]["launch_settings"]["launch_quantization"] == "OFF", audible
        print("authored", out)


def verify(args):
    out = args.output.resolve()
    from live_java_recreate import audit
    with JavaSong(bars=4) as s:
        s.select_project(args.project.resolve())
        assert Path(s.project_info()["path"]) == args.project.resolve()
        s.stop()
        before = json.loads((out / "before.json").read_text(encoding="utf-8"))
        report = audit(before, s.dump())
        (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        assert not report["differences"], report

        midi = s.track_at("Launcher/MIDI")
        slot = midi.read_launcher_slot(0)
        assert slot["clip"]["name"] == "Chord" and len(slot["clip"]["notes"]) == 2, slot
        settings = slot["clip"]["launch_settings"]
        assert settings["launch_quantization"] == "BAR" and settings["launch_mode"] == "SYNCED", settings
        assert settings["next_action"] == "PLAY_NEXT" and settings["next_action_time"] == 2, settings
        assert settings["use_loop_start_as_quantization_reference"] is True, settings

        audio = s.track_at("Launcher/Audio")
        slot = audio.read_launcher_slot(0)
        assert slot["clip"]["name"] == "Tone" and slot["clip"]["duration"] == 8, slot
        event = slot["clip"]["audio_events"][0]
        gain = next(e for e in event["expressions"] if e["expression"] == "gain")
        assert abs(gain["points"][0]["value"] - 10 ** (-6 / 20)) < 1e-6, event
        assert slot["clip"]["launch_settings"]["launch_quantization"] == "OFF", slot
        assert audio.read_launcher_slot(1)["clip"] is None, "deleted slot clip reappeared"
        assert audio.read_launcher_slot(2)["has_stop"] is True, audio.read_launcher_slot(2)

        if args.render:
            ensure_audio(s)
            audio.launch_slot(0)
            time.sleep(1.5)
            assert audio.read_launcher_slot(0)["playing"] is True
            audible_launch(s, out / "reopened-launch.wav")
            audio.stop_launcher()
        print("verified", out)


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
    if args.verify:
        verify(args)
    else:
        author(args)


if __name__ == "__main__":
    main()
