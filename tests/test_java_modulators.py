"""Modulator editing wire contract against a fake Java bridge."""
import pytest

from openwig.java_song import JavaSong
from test_java_song import FakeJavaBridge

LFO = "ad947004-f1d3-40a1-bd15-3ec721ee7c65"


class ModulatorBridge(FakeJavaBridge):
    def request(self, method, params=None, timeout=None):
        params = params or {}
        if method == "modulator.catalog":
            self.calls.append((method, params))
            return {"modulators": [{"name": "LFO", "modulator_id": LFO},
                                   {"name": "Steps", "modulator_id": "5ff7e6e8-5158-46d4-9ba5-2d412750334c"}]}
        if method == "device.modulator_parameters":
            self.calls.append((method, params))
            return {"modulator": params["modulator"], "values": params.get("values", {})}
        if method.startswith("device.modulator_"):
            self.calls.append((method, params))
            return params
        if method == "device.read":
            self.calls.append((method, params))
            return {"modulators": [{"index": 0}]}
        return super().request(method, params, timeout)


@pytest.fixture(autouse=True)
def no_settle(monkeypatch):
    """add_modulator paces 250 ms for the engine; unit tests skip the wait."""
    import time
    monkeypatch.setattr(time, "sleep", lambda seconds: None)


def track(path="Group/Tone"):
    bridge = ModulatorBridge()
    handle = JavaSong(bridge=bridge).track_at(path)
    bridge.calls.clear()
    return bridge, handle


def test_modulator_requests_keep_track_and_chain_address():
    bridge, t = track()
    chain = t.chain(1, "CONTENTS/FX/Chain")
    bridge.calls.clear()
    chain.add_modulator(2, "LFO")
    chain.add_modulator(2, LFO.upper(), x=3, y=2, values={"BIPOLAR": False, "RATE": .25})
    chain.set_modulator_parameters(2, 0, {"RATE": .5, "BIPOLAR": False, "TIMEBASE": 1})
    chain.modulator_parameters(2, 0, unit="HERTZ")
    chain.bind_modulator(2, 0, "AMPLITUDE", -.25, source="LFO", target_device=1)
    chain.unbind_modulator(2, 0, "AMPLITUDE")
    chain.move_modulator(2, 0, 4, 1)
    chain.delete_modulator(2, 0)
    base = {"path": "Group/Tone", "chain": [{"device": 1, "component": "CONTENTS/FX/Chain"}], "device": 2}
    assert bridge.calls == [
        ("modulator.catalog", {}),
        ("device.modulator_add", {**base, "modulator_id": LFO}),
        ("device.modulator_add", {**base, "modulator_id": LFO, "x": 3, "y": 2,
                                  "values": {"BIPOLAR": False, "RATE": .25}}),
        ("device.modulator_parameters", {**base, "modulator": 0, "values": {"RATE": .5, "BIPOLAR": False, "TIMEBASE": 1}, "unit": None}),
        ("device.modulator_parameters", {**base, "modulator": 0, "unit": "HERTZ"}),
        ("device.modulator_bind", {**base, "modulator": 0, "target_parameter": "AMPLITUDE", "amount": -.25,
                                   "source": "LFO", "target_device": 1}),
        ("device.modulator_unbind", {**base, "modulator": 0, "target_parameter": "AMPLITUDE"}),
        ("device.modulator_move", {**base, "modulator": 0, "x": 4, "y": 1}),
        ("device.modulator_delete", {**base, "modulator": 0})]


def test_catalog_and_read():
    bridge, t = track("Tone")
    assert t.s.modulator_catalog()[0] == {"name": "LFO", "modulator_id": LFO}
    assert t.modulators(0) == [{"index": 0}]
    assert bridge.calls[-1] == ("device.read", {"path": "Tone", "device": 0})


@pytest.mark.parametrize("call", [
    lambda t: t.add_modulator(0, "Unknown Modulator"),
    lambda t: t.add_modulator(0, ""),
    lambda t: t.add_modulator(0, None),
    lambda t: t.add_modulator(-1, LFO),
    lambda t: t.add_modulator(0, LFO, x=1),
    lambda t: t.add_modulator(0, LFO, x=1, y=3),
    lambda t: t.add_modulator(0, LFO, x=-1, y=0),
    lambda t: t.add_modulator(0, LFO, x=True, y=0),
    lambda t: t.add_modulator(0, LFO, values={}),
    lambda t: t.add_modulator(0, LFO, values={"RATE": "fast"}),
    lambda t: t.set_modulator_parameters(0, 0, {}),
    lambda t: t.set_modulator_parameters(0, 0, {"RATE": float("nan")}),
    lambda t: t.set_modulator_parameters(0, 0, {"RATE": "fast"}),
    lambda t: t.set_modulator_parameters(0, -1, {"RATE": .5}),
    lambda t: t.bind_modulator(0, 0, "AMPLITUDE", 1.5),
    lambda t: t.bind_modulator(0, 0, "AMPLITUDE", True),
    lambda t: t.bind_modulator(0, 0, "AMPLITUDE", float("inf")),
    lambda t: t.bind_modulator(0, 0, "", .5),
    lambda t: t.bind_modulator(0, 0, "../AMPLITUDE", .5),
    lambda t: t.bind_modulator(0, 0, "AMPLITUDE", .5, source=""),
    lambda t: t.bind_modulator(0, 0, "AMPLITUDE", .5, target_device=-1),
    lambda t: t.unbind_modulator(0, .5, "AMPLITUDE"),
    lambda t: t.move_modulator(0, 0, 0, 3),
    lambda t: t.move_modulator(0, 0, 4096, 0),
    lambda t: t.delete_modulator(0, None),
])
def test_invalid_modulator_calls_reject_before_rpc(call):
    bridge, t = track("Tone")
    with pytest.raises(ValueError):
        call(t)
    assert all(method == "modulator.catalog" for method, _ in bridge.calls)
