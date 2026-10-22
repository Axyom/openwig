"""Manual FM-4 round-trip check on an open disposable Bitwig 6.1.5 project.

Run with ``PYTHONPATH=src python tests/live_java_parameters.py``. Creates and
deletes one probe track; never run against a project with unsaved work.
"""

import time
from types import SimpleNamespace

from openwig.bridge import BridgeClient, BridgeError
from openwig.java_song import JavaTrack

bridge = BridgeClient(port=7778)
bridge.start()
assert bridge.wait_connected(40), "Java bridge did not connect"
try:
    for _ in range(100):
        if bridge.request("document.status")["active"]:
            break
        time.sleep(0.2)
    else:
        raise AssertionError("disposable project did not open")
    # The active flag can arrive before the project's tracks finish loading.
    time.sleep(3)
    track = bridge.request("track.count")["count"]
    bridge.request("track.create", {"type": "instrument", "index": -1, "name": "Parameter Live Check"})
    try:
        assert bridge.request("track.snapshot", {"index": track})["name"] == "Parameter Live Check"
        probe = JavaTrack(SimpleNamespace(b=bridge), track, "Parameter Live Check")
        device_index = bridge.request("device.count", {"track": track})["count"]
        bridge.request("device.insert_factory", {"track": track, "name": "FM-4"})
        for _ in range(100):
            available = probe.list_parameters(device_index)
            paths = {item["path"]: item["kind"] for item in available}
            if paths.get("OUTPUT") == "double" and paths.get("OP1_ENABLE") == "boolean":
                break
            time.sleep(0.2)
        else:
            raise AssertionError(f"FM-4 parameters did not load: {paths}")
        print("discovered", len(available), "parameter paths")
        enums = [path for path, kind in paths.items() if kind == "enum"]
        if enums:
            enum_name = enums[0]
            enum_value = probe.get_parameters(device_index, [enum_name])[enum_name]
            probe.parameters(device_index, **{enum_name: enum_value})
            assert probe.get_parameters(device_index, [enum_name])[enum_name] == enum_value
            print("enum", enum_name, enum_value)
        before = probe.get_parameters(device_index, ["OUTPUT", "OP1_ENABLE"])
        print("before", before)
        probe.parameters(device_index, OUTPUT=0.35, OP1_ENABLE=False)
        observed = probe.get_parameters(device_index, ["OUTPUT", "OP1_ENABLE"])
        print("after", observed)
        assert abs(observed["OUTPUT"] - 0.35) < 0.01
        assert observed["OP1_ENABLE"] is False
        percent = probe.get_parameters(device_index, ["OUTPUT", "OP1_ENABLE"], unit="PERCENTAGE_OF_RANGE")
        print("percent", percent)
        assert abs(percent["OUTPUT"] - 35) < 1
        try:
            bridge.request("device.set_parameters", {
                "track": track, "device": device_index, "params": {"OUTPUT": 1.1},
            })
        except BridgeError:
            pass
        else:
            raise AssertionError("out-of-range normalized parameter was accepted")
        assert abs(probe.get_parameters(device_index, ["OUTPUT"])["OUTPUT"] - 0.35) < 0.01
        try:
            bridge.request("device.set_parameters", {
                "track": track, "device": device_index,
                "params": {"OUTPUT": 0.6, "NOT_A_PARAMETER": 0.2},
            })
        except BridgeError:
            pass
        else:
            raise AssertionError("unknown parameter was accepted")
        assert abs(probe.get_parameters(device_index, ["OUTPUT"])["OUTPUT"] - 0.35) < 0.01
        print("live parameter check: PASS")
    finally:
        bridge.request("track.delete", {"index": track})
finally:
    bridge.stop()
