"""Manual modulator gate: insert, edit, map, unmap, delete, load, rejection and persistence.

A synthetic 440 Hz sine plays through a Tool device whose LFO modulates its
AMPLITUDE; a 660 Hz sine checks an FX Layer LFO mapped to a Tool nested in its
layer. Each step is measured from process loopback as the tone level per
100 ms frame; modulation depth is 20*log10(p95 / p5) of those levels (dB).
"""
import argparse
import ctypes
import json
import time
import wave
from pathlib import Path

import numpy as np
from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_audio_capture import capture
from live_java_launcher import ensure_audio, wait_for_document
from live_java_recreate import audit

TONE = 440.0
NESTED_TONE = 660.0
# A unipolar LFO with a negative amount only attenuates. Boosting a full-scale
# tone (+36 dB at amount .5 on Tool AMPLITUDE) engages output limiting, which
# then ducks every other tone in the capture.
UNIPOLAR = {"RATE": .5, "BIPOLAR": False}
NESTED_TARGET = "CHAIN_LIST/CHAIN0/DEVICE_CHAIN/0/CONTENTS/AMPLITUDE"
FRAME = .1


def envelope(data, rate, frequency, start=1.0, stop=6.5):
    """Tone amplitude per Hann-windowed 100 ms frame."""
    size = int(FRAME * rate)
    window = np.hanning(size)
    frequencies = np.fft.rfftfreq(size, 1 / rate)
    band = abs(frequencies - frequency) <= 12
    levels = []
    for begin in range(int(start * rate), int(stop * rate) - size, size):
        spectrum = np.abs(np.fft.rfft(data[begin:begin + size] * window))
        levels.append(spectrum[band].max() * 2 / window.sum() / np.sqrt(2))
    return np.array(levels)


def modulation(levels):
    low, high = np.percentile(levels, 5), np.percentile(levels, 95)
    centered = levels - levels.mean()
    spectrum = np.abs(np.fft.rfft(centered * np.hanning(len(centered))))
    peak = int(spectrum[1:].argmax()) + 1
    return dict(depth_db=float(20 * np.log10(high / max(low, 1e-9))),
                index=float((high - low) / (high + low)) if high + low > 0 else 0.0,
                mean=float(levels.mean()), p5=float(low), p95=float(high),
                envelope_hz=float(np.fft.rfftfreq(len(levels), FRAME)[peak]))


def measure(s, out, options, label, *, at_least=None, at_most=None, audible=True, frequency=TONE,
            attenuate_only=False):
    rate, data = capture(s, out / f"{label}.wav", **options)
    result = modulation(envelope(data, rate, frequency))
    (out / f"{label}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(label, json.dumps(result))
    if audible:
        assert result["mean"] > .005, (label, result)
    if at_least is not None:
        assert result["depth_db"] >= at_least, (label, result)
    if at_most is not None:
        assert result["depth_db"] <= at_most, (label, result)
    if attenuate_only:
        # A unipolar LFO with a negative amount never exceeds the unmodulated level.
        assert result["p95"] <= .0245, (label, result)
    return result


def process_alive(pid):
    handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    try:
        code = ctypes.c_ulong()
        ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
        return code.value == 259  # STILL_ACTIVE
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)


def sine(file, seconds, frequency=TONE, rate=48000):
    with wave.open(str(file), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes((12000 * np.sin(2 * np.pi * frequency * np.arange(int(rate * seconds)) / rate)).astype("<i2").tobytes())


def tone_bindings(tone):
    return [(b["target_parameter"], round(b["amount_normalized"], 6))
            for m in tone.modulators(0) for b in m["bindings"]]


NESTED_BINDING = ("AMPLITUDE", {"path": "Nested", "chain": 0, "device": 0}, -.5)


def nested_bindings(track):
    return [(b["target_parameter"], b["target_owner"], round(b["amount_normalized"], 6))
            for m in track.modulators(0) for b in m["bindings"]]


def nested_phase(s, out, options):
    """An FX Layer LFO maps into its own layer; a sibling device is out of scope."""
    nested = s.audio_track("Nested")
    sine(out / "tone-660.wav", 8.5, NESTED_TONE)
    nested.import_audio(out / "tone-660.wav", start=0, duration=16, name="Tone 660")
    nested.add_device("FX Layer")
    nested.chain(0, "CONTENTS/CHAIN_LIST/CHAIN0").add_device("Tool")
    nested.add_device("Tool")
    nested.add_modulator(0, "LFO")
    nested.set_modulator_parameters(0, 0, UNIPOLAR)
    before = nested.read_device(0)
    try:
        nested.bind_modulator(0, 0, "AMPLITUDE", .5, target_device=1)
    except BridgeError:
        pass
    else:
        raise AssertionError("sibling device mapping accepted")
    assert nested.read_device(0) == before
    measure(s, out, options, "nested-unmapped", at_most=1, frequency=NESTED_TONE)
    nested.bind_modulator(0, 0, NESTED_TARGET, -.5)
    assert nested_bindings(nested) == [NESTED_BINDING], nested_bindings(nested)
    measure(s, out, options, "nested-mapped", at_least=20, frequency=NESTED_TONE, attenuate_only=True)


def load_phase(s, out, catalog, engine_pid):
    """Several modulators and mappings on playing instruments, edited during playback."""
    names = ["LFO", "Random", "Steps", "ADSR", "Beat LFO"]
    tracks = []
    # Keys keep low harmonics away from the 440/660 Hz measurement bands.
    for n, key in enumerate((53, 55, 60)):
        t = s.track(f"Load {n}", "Polysynth")
        t.volume(.2)
        t.clip([(key, 0, 16, .7)], dur=16)
        tracks.append(t)
    log = []
    s.play(loop=True)
    try:
        for round_ in range(3):
            for n, t in enumerate(tracks):
                for name in names:
                    t.add_modulator(0, name)
                mods = t.modulators(0)
                for m in mods[-len(names):]:
                    for i, parameter in enumerate(("F1FREQ", "OUTPUT", "PAN")):
                        t.bind_modulator(0, m["index"], parameter, (-1) ** i * .1 * (1 + round_ + i))
                for m in mods[-len(names):]:
                    t.bind_modulator(0, m["index"], "F1FREQ", .05)
                assert s.b.request("document.audio_status")["connected"]
            for t in tracks:
                mods = t.modulators(0)
                for m in reversed(mods[-len(names):]):
                    if m["index"] % 2:
                        t.unbind_modulator(0, m["index"], "PAN")
                    else:
                        t.delete_modulator(0, m["index"])
            counts = [len(t.modulators(0)) for t in tracks]
            bindings = [sum(len(m["bindings"]) for m in t.modulators(0)) for t in tracks]
            log.append(dict(round=round_, modulators=counts, bindings=bindings,
                            engine_alive=process_alive(engine_pid)))
            print("load round", log[-1])
            assert log[-1]["engine_alive"], log[-1]
            time.sleep(.5)
    finally:
        s.stop()
    (out / "load.json").write_text(json.dumps(log, indent=2), encoding="utf-8")
    return tracks


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
    with JavaSong(bars=4) as s:
        s.select_project(target)
        assert Path(s.project_info()["path"]) == target
        s.stop(); ensure_audio(s)
        tone = s.track_at("Tone")
        if a.verify:
            report = audit(json.loads((out / "before.json").read_text(encoding="utf-8")), s.dump())
            (out / "persistence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            assert not report["differences"], report
            assert tone_bindings(tone) == [("AMPLITUDE", -.5)], tone_bindings(tone)
            assert nested_bindings(s.track_at("Nested")) == [NESTED_BINDING]
            measure(s, out, options, "reopened", at_least=20, attenuate_only=True)
            measure(s, out, options, "reopened-nested", at_least=20, frequency=NESTED_TONE, attenuate_only=True)
            return
        s.clear(); s.tempo(120)
        catalog = {m["name"]: m["modulator_id"] for m in s.modulator_catalog()}
        assert {"LFO", "ADSR", "Steps", "Macro", "Random"} <= set(catalog), catalog
        tone = s.audio_track("Tone")
        sine(out / "tone-440.wav", 8.5)
        tone.import_audio(out / "tone-440.wav", start=0, duration=16, name="Tone 440")
        tone.add_device("Tool")
        assert tone.modulators(0) == []
        added = tone.add_modulator(0, "LFO")
        assert added == {"modulator": 0, "count": 1}, added
        lfo = tone.modulators(0)[0]
        assert lfo["modulator_id"] == catalog["LFO"] and (lfo["x"], lfo["y"]) == (0, 0), lfo
        # Fixed free-running rate; values are normalized parameter positions.
        values = tone.set_modulator_parameters(0, 0, {**UNIPOLAR, "DEPTH": 1})
        assert abs(values["RATE"] - .5) < 1e-9 and values["BIPOLAR"] is False, values
        measure(s, out, options, "unmapped", at_most=1)
        result = tone.bind_modulator(0, 0, "AMPLITUDE", -.5)
        assert result["source"] and abs(result["amount"] + .5) < 1e-9, result
        strong = measure(s, out, options, "mapped-0.5-down", at_least=20, attenuate_only=True)
        tone.bind_modulator(0, 0, "AMPLITUDE", -.15)
        assert tone_bindings(tone) == [("AMPLITUDE", -.15)], tone_bindings(tone)
        weak = measure(s, out, options, "mapped-0.15-down", at_least=6, attenuate_only=True)
        assert weak["depth_db"] < strong["depth_db"] * .75, (weak, strong)
        tone.set_modulator_parameters(0, 0, {"DEPTH": 0})
        measure(s, out, options, "depth-0", at_most=1)
        tone.set_modulator_parameters(0, 0, {"DEPTH": 1})
        tone.unbind_modulator(0, 0, "AMPLITUDE")
        assert tone_bindings(tone) == []
        measure(s, out, options, "unbound", at_most=1)
        tone.bind_modulator(0, 0, "AMPLITUDE", -.5)
        second = tone.add_modulator(0, "Steps", x=2, y=1)
        assert second["modulator"] == 1 and (tone.modulators(0)[1]["x"], tone.modulators(0)[1]["y"]) == (2, 1)
        moved = tone.move_modulator(0, 0, 3, 2)
        assert (moved["x"], moved["y"]) == (3, 2) and tone_bindings(tone) == [("AMPLITUDE", -.5)]
        tone.delete_modulator(0, 1)
        nested_phase(s, out, options)
        load_phase(s, out, catalog, a.engine_pid)
        measure(s, out, options, "after-load", at_least=20, attenuate_only=True)
        tone.delete_modulator(0, 0)
        assert tone.modulators(0) == [] and tone_bindings(tone) == []
        measure(s, out, options, "deleted", at_most=1)
        # Initial values staged before insertion; mapped and captured at once.
        added = tone.add_modulator(0, catalog["LFO"], values=UNIPOLAR)
        assert added["values"]["BIPOLAR"] is False and abs(added["values"]["RATE"] - .5) < 1e-9, added
        tone.bind_modulator(0, 0, "AMPLITUDE", -.5)
        tone.add_modulator(0, "Macro")
        measure(s, out, options, "final", at_least=20, attenuate_only=True)
        before = s.dump()
        base = dict(path="Tone", device=0)
        for method, params in [
            ("device.modulator_add", dict(modulator_id="00000000-0000-0000-0000-000000000000")),
            ("device.modulator_add", dict(modulator_id=catalog["LFO"], x=0, y=0)),
            ("device.modulator_add", dict(modulator_id=catalog["LFO"], x=5, y=3)),
            ("device.modulator_add", dict(modulator_id="not-a-uuid")),
            ("device.modulator_add", dict(modulator_id=catalog["LFO"], values={"RATE": .2, "NOPE": 1})),
            ("device.modulator_add", dict(modulator_id=catalog["LFO"], values={"RATE": 2})),
            ("device.modulator_parameters", dict(modulator=0, values={"RATE": .2, "NOPE": 1})),
            ("device.modulator_parameters", dict(modulator=0, values={"DEPTH": .2, "RATE": 2})),
            ("device.modulator_parameters", dict(modulator=0, values={"DEPTH": .2, "TIMEBASE": 99})),
            ("device.modulator_parameters", dict(modulator=0, values={"DEPTH": .2, "BIPOLAR": 1})),
            ("device.modulator_parameters", dict(modulator=9)),
            ("device.modulator_bind", dict(modulator=0, target_parameter="AMPLITUDE", amount=1.5)),
            ("device.modulator_bind", dict(modulator=0, target_parameter="NOPE", amount=.2)),
            ("device.modulator_bind", dict(modulator=0, target_parameter="AMPLITUDE", amount=.2, source="NOPE")),
            ("device.modulator_bind", dict(modulator=0, target_parameter="AMPLITUDE", amount=.2, target_device=4)),
            ("device.modulator_bind", dict(modulator=5, target_parameter="AMPLITUDE", amount=.2)),
            ("device.modulator_unbind", dict(modulator=0, target_parameter="VOLUME")),
            ("device.modulator_move", dict(modulator=0, x=0, y=1)),
            ("device.modulator_move", dict(modulator=0, x=-1, y=0)),
            ("device.modulator_delete", dict(modulator=2)),
            ("device.modulator_delete", dict(modulator=-1)),
        ]:
            try:
                s.b.request(method, dict(base, **params))
            except BridgeError:
                pass
            else:
                raise AssertionError(f"invalid modulator edit accepted: {method} {params}")
            assert s.dump() == before, (method, params)
        assert process_alive(a.engine_pid)
        (out / "before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        s.save_project(out / "saved" / "project.bwproject")


if __name__ == "__main__":
    main()
