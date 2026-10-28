"""Manual transport + audible WAV check in an open disposable Java-bridge project.

Run ``PYTHONPATH=src python tests/live_java_render.py``. Uses a temporary FM-4
track, starts playback, and writes ignored scratch/java-render-smoke.wav.
"""

import time
from pathlib import Path

from openwig.bridge import BridgeClient
from openwig.java_song import JavaSong


bridge = BridgeClient(port=7778)
bridge.start()
assert bridge.wait_connected(40), "Java bridge did not connect"
try:
    for _ in range(100):
        if bridge.request("document.status")["active"]:
            break
        time.sleep(0.2)
    time.sleep(3)
    original_tempo = bridge.request("transport.get_tempo")["bpm"]
    song = JavaSong(tempo=120, bars=1, bridge=bridge)
    before = song.transport()
    track = song.track("Java Render Check", device="FM-4")
    try:
        for _ in range(100):
            if any(item["path"] == "OP1_ENABLE" for item in track.list_parameters()):
                break
            time.sleep(0.2)
        else:
            raise AssertionError("FM-4 did not load")
        track.clip([(60, 0, 1, 0.8), (64, 1, 1, 0.8),
                    (67, 2, 1, 0.8), (72, 3, 0.75, 0.8)])
        song.play(loop=True)
        time.sleep(0.4)
        state = song.transport()
        assert state["playing"] and state["loop"], state
        assert state["beats"] > 0, state
        assert state["loop_start"] == 0 and state["loop_length"] == 4, state
        song.stop()
        assert not song.transport()["playing"]
        output = Path("scratch/java-render-smoke.wav")
        output.parent.mkdir(exist_ok=True)
        result = song.render(output, tail=0.5)
        assert not result["silent"], result
        assert abs(result["seconds"] - 2.5) < 0.05, result
        assert not song.transport()["playing"]
        print("live Java transport/render: PASS", result)
    finally:
        song.stop()
        bridge.request("track.delete", {"index": track.idx})
        bridge.request("transport.set_loop_region", {
            "start": before["loop_start"], "length": before["loop_length"],
        })
        bridge.request("transport.set_loop", {"on": before["loop"]})
        bridge.request("transport.set_position", {"beats": before["beats"]})
        bridge.request("transport.set_tempo", {"bpm": original_tempo})
finally:
    bridge.stop()
