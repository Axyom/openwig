"""Manual nested native-device gate; clears only an explicitly selected fixture."""
import argparse
import json
import wave
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture, tone_rms
from live_java_launcher import ensure_audio, wait_for_document
from live_java_recreate import audit


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
            before = json.loads((out / "before.json").read_text(encoding="utf-8"))
            report = audit(before, s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
        else:
            s.clear(); s.tempo(150)
            t = s.audio_track("Nested")
            t.add_device("FX Layer")
            assert t.chain_banks(0) == [{"component": "CONTENTS/CHAIN_LIST", "count": 1, "can_create_layer": True}]
            first = t.chain(0, "CONTENTS/CHAIN_LIST/CHAIN0")
            first.add_device("FX Layer")
            deep = first.chain(0, "CONTENTS/CHAIN_LIST/CHAIN0")
            deep.add_device("Tool").rename_device(0, "Deep gain")
            deep.parameters(0, unit="DECIBEL", VOLUME=-6)
            assert abs(deep.get_parameters(0, ["VOLUME"], unit="DECIBEL")["VOLUME"] + 6) < 1e-5
            deep.enable_device(0, False).enable_device(0, True)
            deep.add_device("Tool").delete_device(1)
            assert len(deep.devices()) == 1
            added = t.create_layer(0, "CONTENTS/CHAIN_LIST")
            assert added["count"] == 2
            second = t.chain(0, added["component"])
            second.add_device("Tool").parameters(0, unit="DECIBEL", VOLUME=-120)
            first_path = "CONTENTS/CHAIN_LIST/CHAIN0"
            t.update_layer(0, first_path, name="Audible", pan=.2, volume_db=-3, muted=True)
            layer = t.read_layer(0, first_path)
            assert layer["name"] == layer["title"] == "Audible"
            assert abs(layer["mixer"]["volume_db"] + 3) < 1e-6 and layer["mixer"]["muted"]
            t.update_layer(0, first_path, pan=0, volume=1, muted=False)
            t.update_layer(0, added["component"], name="Muted", muted=True)
            loud = t.create_layer(0, "CONTENTS/CHAIN_LIST")["component"]
            t.update_layer(0, loud, name="Delete audible layer")
            t.chain(0, loud).add_device("Tool")
            moved = t.move_layer(0, first_path, 2)
            assert moved["component"] == "CONTENTS/CHAIN_LIST/CHAIN2" and moved["name"] == "Audible"
            assert [c["layer"]["name"] for c in t.read_device(0)["chains"]] == ["Muted", "Delete audible layer", "Audible"]
            assert t.move_layer(0, moved["component"], 0)["component"] == first_path
            grid = s.track("Grid chains").add_device("Poly Grid")
            grid.chain(0, "CONTENTS/PRE_FX/Chain").add_device("Note Transpose")
            grid.chain(0, "CONTENTS/POST_FX/Chain").add_device("Tool")
            before = s.dump()
            for chain in [None, "wrong", [{"device": .5, "component": "CONTENTS/CHAIN_LIST/CHAIN0"}],
                          [{"device": 0, "component": "CONTENTS/CHAIN_LIST"}],
                          [{"device": 0, "component": "CONTENTS/missing"}]]:
                try:
                    s.b.request("device.insert_factory", {"path": "Nested", "chain": chain, "name": "Tool"})
                except BridgeError:
                    pass
                else:
                    raise AssertionError(f"invalid chain accepted: {chain}")
                assert s.dump() == before
            sample = out / "tone.wav"; rate = 48000
            with wave.open(str(sample), "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
                w.writeframes((12000 * np.sin(2 * np.pi * 440 * np.arange(rate * 3) / rate)).astype("<i2").tobytes())
            t.import_audio(sample)
            rate, parallel = capture(s, out / "parallel-before-delete.wav", capture_exe=a.process_capture, engine_pid=a.engine_pid)
            parallel_rms = tone_rms(parallel, rate, 1., 440)
            assert t.delete_layer(0, loud)["count"] == 2
            rate, removed = capture(s, out / "parallel-after-delete.wav", capture_exe=a.process_capture, engine_pid=a.engine_pid)
            removed_rms = tone_rms(removed, rate, 1., 440)
            assert abs(removed_rms / parallel_rms - .501187 / 1.501187) < .03, (removed_rms, parallel_rms)
            # Validate the entire batch before changing a layer name or level.
            before = s.dump()
            for method, changes in [
                ("device.layer_update", dict(changes=dict(name="must not change", pan=2))),
                ("device.layer_update", dict(changes=dict(volume=.5, volume_db=-6))),
                ("device.layer_move", dict(index=10)),
                ("device.layer_move", dict(index=.5)),
            ]:
                try:
                    s.b.request(method, dict(path="Nested", device=0, component=first_path, **changes))
                except BridgeError:
                    pass
                else:
                    raise AssertionError(f"invalid layer edit accepted: {method}")
                assert s.dump() == before
            deep.enable_device(0, False)
            rate, baseline = capture(s, out / "baseline.wav", capture_exe=a.process_capture, engine_pid=a.engine_pid)
            baseline_rms = tone_rms(baseline, rate, 1., 440)
            deep.enable_device(0, True)
        label = "reopened" if a.verify else "live"
        rate, data = capture(s, out / f"{label}.wav", capture_exe=a.process_capture, engine_pid=a.engine_pid)
        rms = tone_rms(data, rate, 1., 440)
        assert rms > .005, rms
        if not a.verify:
            ratio = rms / baseline_rms
            assert abs(ratio - 10 ** (-6 / 20)) < .03, (rms, baseline_rms, ratio)
        (out / f"{label}-audio.json").write_text(json.dumps({"tone_rms": rms}), encoding="utf-8")
        if not a.verify:
            (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
            s.save_project(out / "saved" / "project.bwproject")


if __name__ == "__main__":
    main()
