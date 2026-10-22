"""Manual Grid authoring, audible cable/pitch changes and native persistence gate."""
import argparse
import json
from pathlib import Path

from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture, tone_rms
from live_java_launcher import ensure_audio, wait_for_document
from live_java_recreate import audit


def measure(s, out, options, label, frequency=None):
    rate, audio = capture(s, out / f"{label}.wav", **options)
    levels = {str(f): tone_rms(audio, rate, 1., f) for f in (261.625565, 523.251131, 440, 880)}
    (out / f"{label}.json").write_text(json.dumps(levels), encoding="utf-8")
    if frequency is None:
        assert max(levels.values()) < .001, levels
    else:
        assert levels[str(frequency)] > .005, levels
        if frequency == 523.251131:
            assert levels["261.625565"] < levels["523.251131"] / 20, levels


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--project", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--disposable", action="store_true")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--note-tracking", action="store_true",
                   help="Run the open immediate note-tracking pitch regression (may fail before reopen)")
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
            measure(s, out, options, "reopened", 880 if a.note_tracking else 523.251131)
            return
        s.clear(); s.tempo(150)
        t = s.track("Authored Grid", "Poly Grid")
        defaults = t.read_grid()["modules"]
        assert [m["name"] for m in defaults] == ["Union", "ADSR", "Audio Out"]
        for m in reversed(defaults):
            t.delete_grid_module(0, m["component"])
        assert not t.read_grid()["modules"]
        addresses = []
        for m, x in zip(defaults, (0, 5, 10)):
            addresses.append(t.add_grid_module(0, m["module_id"], x, 0)["component"])
        osc, env, output = addresses
        t.move_grid_module(0, osc, -4, 0)
        t.connect_grid(0, env + "/CONTENTS/IN", osc + "/CONTENTS/OUT")
        t.connect_grid(0, output + "/CONTENTS/IN", env + "/CONTENTS/OUT")
        t.parameters(0, **{env.removeprefix("CONTENTS/") + "/CONTENTS/SUSTAIN": 1})
        # Fixed pitch isolates module processing from the open live note-tracking
        # regression (native save/reopen restores correct note-tracked pitch).
        t.parameters(0, **{osc.removeprefix("CONTENTS/") + "/CONTENTS/KEYTRACK": a.note_tracking})
        t.clip([(69, 0, 12, .8)], dur=12)
        measure(s, out, options, "authored", 440 if a.note_tracking else 261.625565)
        pitch = osc.removeprefix("CONTENTS/") + "/CONTENTS/PITCH"
        t.parameters(0, unit="RATIO", **{pitch: 2})
        assert abs(t.get_parameters(0, [pitch], unit="RATIO")[pitch] - 2) < 1e-6
        measure(s, out, options, "pitch", 880 if a.note_tracking else 523.251131)
        t.connect_grid(0, output + "/CONTENTS/IN", None)
        measure(s, out, options, "disconnected")
        t.connect_grid(0, output + "/CONTENTS/IN", env + "/CONTENTS/OUT")
        extra = t.add_grid_module(0, defaults[0]["module_id"], 0, 5)["component"]
        t.connect_grid(0, env + "/CONTENTS/IN", extra + "/CONTENTS/OUT")
        t.delete_grid_module(0, extra)
        assert next(port for port in t.read_grid()["modules"][1]["inputs"]
                    if port["component"].endswith("/IN"))["source"] is None
        t.connect_grid(0, env + "/CONTENTS/IN", osc + "/CONTENTS/OUT")
        before = t.read_device(0)
        for method, changes in [
            ("device.grid_add", dict(module_id=defaults[0]["module_id"], x=5, y=0)),
            ("device.grid_move", dict(component=osc, x=5, y=0)),
            ("device.grid_move", dict(component=osc, x=.5, y=0)),
            ("device.grid_connect", dict(input=env + "/CONTENTS/IN", output=env + "/CONTENTS/IN")),
            ("device.grid_delete", dict(component="CONTENTS/MODULES/99")),
        ]:
            try:
                s.b.request(method, dict(path="Authored Grid", device=0, **changes))
            except BridgeError:
                pass
            else:
                raise AssertionError(f"invalid Grid edit accepted: {method} {changes}")
            assert t.read_device(0) == before
        measure(s, out, options, "final", 880 if a.note_tracking else 523.251131)
        (out / "before.json").write_text(json.dumps(s.dump(), indent=2), encoding="utf-8")
        s.save_project(out / "saved" / "project.bwproject")


if __name__ == "__main__":
    main()
