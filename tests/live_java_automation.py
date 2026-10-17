"""Manual arranger automation check; run only on an open disposable project.

Uses the Java bridge on port 7778, creates/deletes a probe track, and briefly
replaces tempo automation. Existing tempo automation must be empty.
"""
import time

from openwig import JavaSong, BridgeClient, BridgeError


def main():
    bridge = BridgeClient(port=7778)
    bridge.start()
    assert bridge.wait_connected(40)
    track = None
    tempo = None
    try:
        for _ in range(100):
            if bridge.request("document.status")["active"]:
                break
            time.sleep(0.2)
        time.sleep(3)
        assert bridge.request("automation.read", {"param": "tempo"})["count"] <= 1
        tempo = bridge.request("transport.get_tempo")["bpm"]
        song = JavaSong(bridge=bridge)
        track = song.track("Automation Live Check", device="FM-4")
        curve = [(0, 0.2, 0.4, "linear"), (2, 0.8, 0, "hold"), (4, 0.5)]
        track.automate("volume", curve)
        stored = track.automation("volume")
        print("volume", stored)
        assert stored["count"] == 3
        for expected, point in zip(curve, stored["points"]):
            assert abs(point["beat"] - expected[0]) < 1e-5
            assert abs(point["normalized"] - expected[1]) < 1e-5
        assert abs(stored["points"][0]["curvature"] - 0.4) < 1e-5
        assert stored["points"][1]["interpolation"] == "hold"
        track.automate("pan", [(0, 0), (4, 1)])
        pan = track.automation("pan")
        print("pan", pan)
        assert abs(pan["points"][0]["normalized"]) < 1e-5
        assert abs(pan["points"][1]["normalized"] - 1) < 1e-5
        for _ in range(100):
            if any(p["path"] == "OP1_ENABLE" for p in track.list_parameters()):
                break
            time.sleep(0.1)
        track.automate("OUTPUT", [(0, 0.1), (4, 0.9)])
        assert track.automation("OUTPUT")["count"] == 2
        track.automate("volume", [(0, 0.3), (4, 0.7)])
        replacement = track.automation("volume")
        assert replacement["count"] == 2
        for invalid in [[(2, 0.2), (1, 0.5)], [(0, 0.2), (1, 1.1)],
                        [(0, 0.2), (1, 0.5, 0, "bad")]]:
            try:
                track.automate("volume", invalid)
            except BridgeError:
                pass
            else:
                raise AssertionError("invalid curve accepted")
            assert track.automation("volume") == replacement
        song.automate_tempo([(0, 100), (4, 140)])
        tempo_points = bridge.request("automation.read", {"param": "tempo"})
        print("tempo", tempo_points)
        assert tempo_points["count"] == 2
        song.automate_tempo([(0, 120, 0, "hold"), (2, 60, 0, "hold")])
        time.sleep(0.2)
        seconds = bridge.request("transport.beats_to_seconds", {"beats": 4})["seconds"]
        print("tempo-map seconds", seconds)
        assert abs(seconds - 3) < 0.01
        bridge.request("automation.clear", {"param": "tempo"})
        assert bridge.request("automation.read", {"param": "tempo"})["count"] <= 1
        print("live automation check: PASS")
    finally:
        if tempo is not None:
            bridge.request("automation.clear", {"param": "tempo"})
            bridge.request("transport.set_tempo", {"bpm": tempo})
        if track is not None:
            bridge.request("track.delete", {"index": track.idx})
        bridge.stop()


if __name__ == "__main__":
    main()
