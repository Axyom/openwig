"""Experimental Python composition surface for the Bitwig 6.1.5 Java bridge.

This covers only operations verified against a disposable project. The existing
``Song`` remains the complete controller-backed API until feature parity.
"""

from __future__ import annotations

from openwig.bridge import BridgeClient, BridgeError


class JavaSong:
    """Compose tracks and MIDI clips through the independent Java bridge."""

    def __init__(self, tempo: float | None = None, bars: int = 16,
                 bridge: BridgeClient | None = None):
        if bars <= 0:
            raise ValueError("bars must be positive")
        self.bars = bars
        self.total = bars * 4.0
        self.b = bridge or BridgeClient(port=7778)
        self._owns_bridge = bridge is None
        self.b.start()
        if not self.b.wait_connected(10):
            raise BridgeError("Bitwig 6.1.5 Java bridge is not reachable on port 7778")
        found = self.b.request("host.version").get("version")
        if found != "6.1.5":
            raise BridgeError(f"Java bridge requires Bitwig 6.1.5, found {found!r}")
        if not self.b.request("document.status").get("active"):
            raise BridgeError("open a Bitwig project before creating a JavaSong")
        if tempo is not None:
            self.tempo(tempo)

    def close(self) -> None:
        if self._owns_bridge:
            self.b.stop()

    def __enter__(self) -> JavaSong:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    def tempo(self, bpm: float) -> float:
        return self.b.request("transport.set_tempo", {"bpm": float(bpm)})["bpm"]

    def transport(self) -> dict:
        """Read playback position, playing state, and arranger loop region."""
        return self.b.request("transport.snapshot")

    def play(self, loop: bool = True) -> JavaSong:
        """Play the arrangement from beat zero, optionally looping this song."""
        self.b.request("transport.set_position", {"beats": 0.0})
        if loop:
            self.b.request("transport.set_loop_region", {
                "start": 0.0, "length": float(self.total),
            })
        self.b.request("transport.set_loop", {"on": bool(loop)})
        self.b.request("transport.play")
        self._wait_playing(True)
        return self

    def _wait_playing(self, playing: bool) -> None:
        import time

        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.transport()["playing"] == playing:
                return
            time.sleep(0.02)
        raise BridgeError(f"transport did not reach playing={playing}")

    def stop(self) -> JavaSong:
        self.b.request("transport.stop")
        self._wait_playing(False)
        return self

    def render(self, path, *, tail: float = 1.0, lead: float = 0.3) -> dict:
        """Capture one arrangement pass to WAV through system audio loopback.

        This is a real-time recording of the default audio output, including
        other applications using that output. Returns capture duration, sample
        rate, channel count, RMS, and a silence flag. Bitwig's native tempo map
        determines capture duration, including arranger tempo automation.
        """
        import math
        import time
        from openwig.wire.render import render_to_wav

        if not math.isfinite(tail) or tail < 0:
            raise ValueError("tail must be a finite nonnegative duration")
        if not math.isfinite(lead) or lead < 0:
            raise ValueError("lead must be a finite nonnegative duration")
        self.stop()
        # The engine's tempo-map cache follows document edits asynchronously.
        time.sleep(0.2)
        bpm = self.b.request("transport.get_tempo")["bpm"]
        duration = self.b.request("transport.beats_to_seconds", {
            "beats": float(self.total),
        })["seconds"]
        try:
            return render_to_wav(self.b, path, beats=self.total,
                                 tempo=bpm, tail=tail, lead=lead,
                                 duration_seconds=duration, set_tempo=False)
        finally:
            self.stop()

    def clear_main_tracks(self) -> int:
        """Remove all main tracks in the active project and return their count."""
        return self.b.request("project.clear_main_tracks", timeout=65)["deleted"]

    def automate_tempo(self, points) -> JavaSong:
        """Replace arranger tempo automation with beat/BPM points (20..666 BPM)."""
        self.b.request("automation.write", {
            "param": "tempo", "points": [list(point) for point in points],
        })
        return self

    def clear(self) -> dict:
        """Remove main/effect tracks and master devices from the active project."""
        return self.b.request("project.clear", timeout=125)

    def dump(self) -> dict:
        """Read the whole open project.

        Returns metadata, cue markers, and the full track tree: groups with
        children, per-track mixer state, sends (with target names), device
        chains with kinds (native/vst3/vst2/plugin), and arranger clips with
        note contents. Read-only; nothing in the project is modified.
        """
        return self.b.request("project.dump", timeout=125)

    def export_dawproject(self, path, *, embed_media: bool = False) -> dict:
        """Export the open project to a Bitwig ``.dawproject`` archive.

        The DAWproject format is Bitwig's open XML exchange format and covers
        more than ``dump`` (plugin parameters, modulators, routing, note
        expressions, launcher clips). Media files are referenced, not
        embedded, unless ``embed_media`` is true. Returns the written path,
        size, and embedded-file count. The project itself is not modified.
        """
        from pathlib import Path
        return self.b.request("project.export_dawproject",
                              {"path": str(Path(path).resolve()),
                               "embed_media": bool(embed_media)},
                              timeout=310)

    def clear_effect_tracks(self) -> int:
        return self.b.request("project.clear_effect_tracks", timeout=65)["deleted"]

    def effect_track(self, name: str) -> int:
        """Append an effect track and return its zero-based effect-bank index."""
        if not name or not name.strip():
            raise ValueError("effect track name is required")
        index = self.b.request("fxtrack.count")["count"]
        count = self.b.request("fxtrack.create", {"name": name})["count"]
        if count != index + 1:
            raise BridgeError("effect track count did not increase after insertion")
        return index

    def master_device(self, name: str) -> JavaSong:
        """Append a native factory device to the master chain."""
        before = self.b.request("master.device_count")["count"]
        after = self.b.request("master.insert_factory", {"name": name})["count"]
        if after != before + 1:
            raise BridgeError("master device count did not increase after insertion")
        return self

    def track(self, name: str, device: str | None = None,
              kind: str = "instrument") -> JavaTrack:
        if kind not in ("instrument", "audio", "hybrid"):
            raise ValueError("track kind must be instrument, audio, or hybrid")
        if not name or not name.strip():
            raise ValueError("track name is required")
        index = self.b.request("track.count")["count"]
        result = self.b.request("track.create", {
            "type": kind, "index": -1, "name": name,
        })
        if result["count"] != index + 1:
            raise BridgeError("track count did not increase after insertion")
        created = self.b.request("track.snapshot", {"index": index})
        if created["name"] != name:
            raise BridgeError("new track did not have the requested name")
        track = JavaTrack(self, index, name)
        if device is not None:
            track.add_device(device)
        return track

    def audio_track(self, name: str) -> JavaTrack:
        return self.track(name, kind="audio")


class JavaTrack:
    """Main instrument track controlled by the Java bridge."""

    def __init__(self, song: JavaSong, index: int, name: str):
        self.s = song
        self.idx = index
        self.name = name

    def snapshot(self) -> dict:
        return self.s.b.request("track.snapshot", {"index": self.idx})

    def add_device(self, name: str) -> JavaTrack:
        """Append a built-in Bitwig factory device by its exact catalog name."""
        if not name or not name.strip():
            raise ValueError("factory device name is required")
        before = self.s.b.request("device.count", {"track": self.idx})["count"]
        after = self.s.b.request("device.insert_factory", {
            "track": self.idx, "name": name,
        })["count"]
        if after != before + 1:
            raise BridgeError("device count did not increase after insertion")
        return self

    def devices(self) -> list[dict]:
        """Read device indexes, optional display names, and enabled states."""
        return self.s.b.request("device.snapshot", {"track": self.idx})

    def enable_device(self, index: int, on: bool = True) -> JavaTrack:
        self.s.b.request("device.set_enabled", {
            "track": self.idx, "index": int(index), "on": bool(on),
        })
        return self

    def rename_device(self, index: int, name: str) -> JavaTrack:
        self.s.b.request("device.rename", {
            "track": self.idx, "device": int(index), "name": name,
        })
        return self

    def expand_device(self, index: int, on: bool = True) -> JavaTrack:
        self.s.b.request("device.set_expanded", {
            "track": self.idx, "device": int(index), "on": bool(on),
        })
        return self

    def delete_device(self, index: int) -> JavaTrack:
        before = self.s.b.request("device.count", {"track": self.idx})["count"]
        after = self.s.b.request("device.delete", {
            "track": self.idx, "device": int(index),
        })["count"]
        if after != before - 1:
            raise BridgeError("device count did not decrease after deletion")
        return self

    def parameters(self, device_index: int = 0, unit: str | None = None,
                   **values) -> JavaTrack:
        """Set named parameters: floats 0..1, booleans, or enum integer indexes.

        An explicit Bitwig ``Unit`` name changes the scale for float parameters.
        """
        if not values:
            raise ValueError("at least one parameter value is required")
        self.s.b.request("device.set_parameters", {
            "track": self.idx, "device": int(device_index),
            "params": values, "unit": unit,
        })
        return self

    def get_parameters(self, device_index: int, names, unit: str | None = None) -> dict:
        """Read named numeric, boolean, or enum-index parameters.

        Numbers default to normalized 0..1 values. An explicit Bitwig Unit
        (for example ``DECIBEL``) reads numbers in that unit instead.
        """
        return self.s.b.request("device.get_parameters", {
            "track": self.idx, "device": int(device_index),
            "names": [str(n) for n in names], "unit": unit,
        })

    def list_parameters(self, device_index: int = 0) -> list[dict]:
        """List available parameter paths and kinds for a loaded native device."""
        return self.s.b.request("device.list_parameters", {
            "track": self.idx, "device": int(device_index),
        })

    def rename(self, name: str) -> JavaTrack:
        self.s.b.request("track.rename", {"index": self.idx, "name": name})
        self.name = name
        return self

    def automate(self, param: str, points, device_index: int = 0,
                 unit: str | None = None) -> JavaTrack:
        """Replace an arranger lane for volume, pan, or a native parameter path.

        Points are (beat, value[, curvature, 'linear'|'hold']). Numeric values
        default to 0..1, including pan (0.5 is center). Use an explicit Bitwig
        Unit for native parameters when needed. At least two points are required.
        """
        self.s.b.request("automation.write", {
            "track": self.idx, "device": int(device_index), "param": param,
            "unit": unit, "points": [list(point) for point in points],
        })
        return self

    def automation(self, param: str, device_index: int = 0) -> dict:
        """Read stored beats, normalized values, curvature, and interpolation."""
        return self.s.b.request("automation.read", {
            "track": self.idx, "device": int(device_index), "param": param,
        })

    def volume(self, value: float) -> JavaTrack:
        self.s.b.request("track.set_volume", {"index": self.idx, "value": float(value)})
        return self

    def pan(self, value: float) -> JavaTrack:
        """Set pan from -1 (left) through 0 (center) to +1 (right)."""
        self.s.b.request("track.set_pan", {
            "index": self.idx, "value": (float(value) + 1.0) / 2.0,
        })
        return self

    def mute(self, on: bool = True) -> JavaTrack:
        self.s.b.request("track.set_mute", {"index": self.idx, "on": bool(on)})
        return self

    def monitor(self, mode: str = "AUTO") -> JavaTrack:
        """Set monitor mode: OFF, ON, or AUTO."""
        if mode not in ("OFF", "ON", "AUTO"):
            raise ValueError("monitor mode must be OFF, ON, or AUTO")
        self.s.b.request("track.set_monitor", {
            "index": self.idx, "mode": mode,
        })
        return self

    def color(self, r: float, g: float, b: float, a: float = 1.0) -> JavaTrack:
        self.s.b.request("track.set_color", {
            "index": self.idx, "r": float(r), "g": float(g),
            "b": float(b), "a": float(a),
        })
        return self

    def send(self, send_index: int, value: float) -> JavaTrack:
        """Set a send amount from 0 to 1; sends exist per effect track."""
        self.s.b.request("track.set_send", {
            "index": self.idx, "send": int(send_index), "value": float(value),
        })
        return self

    def clip(self, notes, dur: float | None = None, start: float = 0.0) -> JavaTrack:
        """Add an arranger clip using existing ``Note`` or five-field tuples."""
        payload = []
        for note in notes:
            if len(note) < 4:
                raise ValueError("each note needs key, start, duration, and velocity")
            key, beat, length, velocity = note[:4]
            channel = note[4] if len(note) > 4 else 0
            payload.append([int(channel), int(key), float(beat),
                            float(length), float(velocity)])
        self.s.b.request("clip.create_arranger_with_notes", {
            "track": self.idx,
            "start": float(start),
            "duration": self.s.total if dur is None else float(dur),
            "notes": payload,
        })
        return self

    def clips(self, segments) -> JavaTrack:
        for start, duration, notes in segments:
            self.clip(notes, dur=duration, start=start)
        return self
