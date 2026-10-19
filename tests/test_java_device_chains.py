"""Nested addresses must survive every level without becoming track operations."""
import pytest

from openwig.java_song import JavaSong
from test_java_song import FakeJavaBridge


class ChainBridge(FakeJavaBridge):
    def request(self, method, params=None, timeout=None):
        if method == "device.set_parameters":
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
