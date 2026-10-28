"""Nested addresses must survive every level without becoming track operations."""
import pytest

from openwig.java_song import JavaSong
from test_java_song import FakeJavaBridge


class ChainBridge(FakeJavaBridge):
    def request(self, method, params=None, timeout=None):
        if method in {"device.layer_move", "device.layer_delete"}:
            self.calls.append((method, params))
            return {"state": "complete", "data": params}
        if method.startswith("device.layer_"):
            self.calls.append((method, params))
            return params
        if method == "device.set_parameters" or method.startswith("device.grid_"):
            self.calls.append((method, params))
            return params
        return super().request(method, params, timeout)


def test_nested_device_address_and_scope():
    bridge = ChainBridge()
    track = JavaSong(bridge=bridge).track_at("Group/Audio")
    first = track.chain(1, "CONTENTS/CHAIN_LIST/CHAIN0")
    second = first.chain(2, "CONTENTS/FX/Chain")
    bridge.calls.clear()
    second.read_device(3)
    second.parameters(3, VOLUME=.5)
    expected = {"path": "Group/Audio", "chain": [
        {"device": 1, "component": "CONTENTS/CHAIN_LIST/CHAIN0"},
        {"device": 2, "component": "CONTENTS/FX/Chain"}]}
    assert bridge.calls == [
        ("device.read", {**expected, "device": 3}),
        ("device.set_parameters", {**expected, "device": 3, "params": {"VOLUME": .5}, "unit": None})]
    assert not hasattr(second, "delete")
    assert not hasattr(second, "import_audio")
    # Mutating a request must not corrupt the reusable handle.
    bridge.calls[0][1]["chain"][0]["device"] = 99
    assert second._track_params() == expected


@pytest.mark.parametrize("device,component", [
    (-1, "CONTENTS/FX/Chain"), (True, "CONTENTS/FX/Chain"),
    (1.5, "CONTENTS/FX/Chain"), (0, ""), (0, "../Chain"), (0, None),
])
def test_invalid_chain_address_rejected_locally(device, component):
    bridge = FakeJavaBridge()
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        track.chain(device, component)
    assert bridge.calls == []


def test_chain_depth_limit():
    track = JavaSong(bridge=FakeJavaBridge()).track_at("Audio")
    for _ in range(32):
        track = track.chain(0, "CONTENTS/FX/Chain")
    with pytest.raises(ValueError, match="depth"):
        track.chain(0, "CONTENTS/FX/Chain")


def test_grid_operations_preserve_nested_address():
    bridge = ChainBridge()
    chain = JavaSong(bridge=bridge).track_at("Audio").chain(0, "CONTENTS/FX/Chain")
    module = "CONTENTS/MODULES/0"
    module_id = "df0a08cc-68b4-4fb2-a662-05cb09745e37"
    bridge.calls.clear()
    chain.read_grid(1)
    chain.add_grid_module(1, module_id, -4, 0)
    chain.move_grid_module(1, module, 0, 0)
    chain.connect_grid(1, module + "/CONTENTS/IN", None)
    chain.delete_grid_module(1, module)
    base = {"path": "Audio", "chain": [{"device": 0, "component": "CONTENTS/FX/Chain"}], "device": 1}
    assert bridge.calls == [
        ("device.grid_read", base),
        ("device.grid_add", {**base, "module_id": module_id, "x": -4, "y": 0}),
        ("device.grid_move", {**base, "component": module, "x": 0, "y": 0}),
        ("device.grid_connect", {**base, "input": module + "/CONTENTS/IN", "output": None}),
        ("device.grid_delete", {**base, "component": module})]


@pytest.mark.parametrize("call", [
    lambda t: t.add_grid_module(0, "invalid", 0, 0),
    lambda t: t.add_grid_module(0, None, 0, 0),
    lambda t: t.move_grid_module(0, "CONTENTS/MODULES/0", True, 0),
    lambda t: t.move_grid_module(0, "CONTENTS/MODULES/0", .5, 0),
    lambda t: t.move_grid_module(0, "CONTENTS/MODULES/0", 1000001, 0),
    lambda t: t.connect_grid(0, "", None),
    lambda t: t.connect_grid(0, "CONTENTS/MODULES/0/CONTENTS/IN", "../OUT"),
    lambda t: t.delete_grid_module(-1, "CONTENTS/MODULES/0"),
])
def test_invalid_grid_calls_reject_before_rpc(call):
    bridge = ChainBridge()
    track = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        call(track)
    assert not bridge.calls


def test_layer_controls_keep_owner_scope():
    bridge = ChainBridge()
    t = JavaSong(bridge=bridge).track_at("Group/Audio").chain(0, "CONTENTS/FX/Chain")
    bridge.calls.clear()
    path = "CONTENTS/CHAIN_LIST/CHAIN0"
    t.read_layer(1, path)
    t.update_layer(1, path, name="Quiet", volume_db=-6, pan=-.2, muted=True)
    t.move_layer(1, path, 2)
    t.delete_layer(1, path)
    base = {"path": "Group/Audio", "chain": [{"device": 0, "component": "CONTENTS/FX/Chain"}],
            "device": 1, "component": path}
    assert bridge.calls == [
        ("device.layer_read", base),
        ("device.layer_update", {**base, "changes": {"name": "Quiet", "volume_db": -6, "pan": -.2, "muted": True}}),
        ("device.layer_move", {**base, "index": 2}),
        ("device.layer_delete", base)]


@pytest.mark.parametrize("changes", [dict(name=None), dict(pan=2), dict(volume=-1),
    dict(volume_db=float("nan")), dict(volume_db=7), dict(volume=1, volume_db=0),
    dict(muted=1), dict(unknown=1), {}])
def test_invalid_layer_batches_reject_locally(changes):
    bridge = ChainBridge()
    t = JavaSong(bridge=bridge).track_at("Audio")
    bridge.calls.clear()
    with pytest.raises(ValueError):
        t.update_layer(0, "CONTENTS/CHAIN_LIST/CHAIN0", **changes)
    assert not bridge.calls
