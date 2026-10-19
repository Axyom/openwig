"""Manual VST3 insertion/state/parameter gate on an explicitly disposable project."""
import argparse
import json
import time
import wave
from pathlib import Path
from hashlib import sha256

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture, tone_rms
from live_java_launcher import ensure_audio, wait_for_document

PLUGIN_ID = "74F3503C00B24D4E000A6C3100D57595"


def parameters(track, index):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        result = track.plugin_parameters(index)
        if result["available"]:
            return result["values"]
        time.sleep(.1)
    raise AssertionError("installed kHs Haas parameter cache did not become available")


def expected(values):
    assert values["1"] == 0 and abs(values["2"] - .2) < 1e-6, values


def audible(song, out, options, label):
    time.sleep(.4)
    rate, data = capture(song, out / f"{label}.wav", **options)
    rms = tone_rms(data, rate, 1., 440)
    assert rms > .005, rms
    (out / f"{label}.json").write_text(json.dumps(dict(tone_rms=rms)), encoding="utf-8")


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
        p.error("--disposable acknowledges clearing this project")
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
            t = s.track_at("Plugin")
            expected(parameters(t, 0)); expected(parameters(t, 1))
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            after = s.dump()
            from live_java_recreate import audit
            report = audit(before, after)
            # Plugin display strings are asynchronous GUI cache values. Report
            # them explicitly; numerical values, IDs and state blobs are checked.
            display_only = [d for d in report["differences"]
                            if "/parameters/" in d["path"] and d["path"].endswith("/display")]
            report["display_cache_differences"] = display_only
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert all(d in display_only for d in report["differences"]), report
            from live_java_recreate import plugin_states
            assert plugin_states(after) == json.loads((out / "state-hashes.json").read_text(encoding="utf-8"))
            audible(s, out, options, "reopened-plugin")
            return
        s.clear(); s.tempo(150)
        t = s.audio_track("Plugin")
        result = t.insert_plugin("kHs Haas", PLUGIN_ID)
        assert result["device"] == 0 and result["data"]["device_id"] == PLUGIN_ID
        parameters(t, 0)
        t.set_plugin_parameters(0, {1: 0, 2: .2})
        expected(parameters(t, 0))
        assert t.read_device(0)["state_bytes"] > 48
        file = out / "edited.vstpreset"
        exported = t.export_plugin_state(0, file)
        assert exported["bytes"] == file.stat().st_size > 48
        assert file.read_bytes()[8:40].decode("ascii") == PLUGIN_ID
        t.insert_plugin("kHs Haas", PLUGIN_ID, state=file)
        expected(parameters(t, 1))
        # Check a second engine export too; the independent instance's live
        # parameter values above prove the state file was actually accepted.
        restored = out / "restored.vstpreset"
        t.export_plugin_state(1, restored)
        assert restored.stat().st_size > 48
        before = t.read()
        for changes in [{"1": .5, "999999": .2}, {"1": .5, "2": 2}]:
            try:
                s.b.request("device.plugin_parameters", t._track_params(device=0, values=changes))
            except BridgeError:
                pass
            else:
                raise AssertionError("invalid plugin parameter accepted")
            assert t.read() == before
        file_hash = sha256(file.read_bytes()).hexdigest()
        try:
            s.b.request("device.export_plugin_state", t._track_params(device=0, file=str(file)))
        except BridgeError:
            pass
        else:
            raise AssertionError("export overwrote an existing preset")
        assert sha256(file.read_bytes()).hexdigest() == file_hash
        try:
            s.b.request("device.insert_plugin", t._track_params(name="wrong", format="vst3",
                id="00000000000000000000000000000000", state=str(file)))
        except BridgeError:
            pass
        else:
            raise AssertionError("state class ID mismatch accepted")
        assert t.read() == before
        sample = out / "tone.wav"; rate = 48000
        with wave.open(str(sample), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
            w.writeframes((12000 * np.sin(2 * np.pi * 440 * np.arange(rate * 3) / rate)).astype("<i2").tobytes())
        t.import_audio(sample)
        audible(s, out, options, "live-plugin")
        snapshot = s.dump()
        from live_java_recreate import plugin_states
        (out / "before.json").write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
        (out / "state-hashes.json").write_text(json.dumps(plugin_states(snapshot), indent=2), encoding="utf-8")
        saved = s.save_project(out / "saved" / "project.bwproject")
        assert saved["plugin_states"] == 2, saved


if __name__ == "__main__":
    main()
