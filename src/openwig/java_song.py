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

        Includes the full track tree, scenes/slots, mixer and routing state,
        nested devices, resident parameter values, modulator parameters and
        bindings, arranger automation, note expressions and audio events/warps.
        Unavailable plugin caches and the stored-atom Grid boundary are
        explicit. One document event; nothing in the project is modified.
        """
        return self.b.request("project.dump", timeout=125)

    def export_dawproject(self, path, *, embed_media: bool = False) -> dict:
        """Export the open project to a Bitwig ``.dawproject`` archive.

        Bitwig's XML exchange format is the archival/parity baseline, including
        plugin state references. Media files are referenced unless
        ``embed_media`` is true. Returns the written path, size, and embedded
        file count. The project itself is not modified.
        """
        from pathlib import Path
        return self.b.request("project.export_dawproject",
                              {"path": str(Path(path).resolve()),
                               "embed_media": bool(embed_media)},
                              timeout=310)

    def import_dawproject(self, path) -> dict:
        """Append a ``.dawproject`` archive into the open project.

        Verified for synthetic groups, notes and metadata; media/plugin state
        restoration is unverified. Existing scenes and markers remain.
        """
        from pathlib import Path
        return self.b.request("project.import_dawproject",
                              {"path": str(Path(path).resolve())},
                              timeout=310)

    def set_metadata(self, **fields) -> dict:
        """Set project metadata fields (title, artist, album, writer, producer, year, genre, copyright, comment)."""
        if not fields:
            raise ValueError("at least one metadata field is required")
        return self.b.request("project.set_metadata", dict(fields))

    def add_cue_marker(self, beat: float, name: str,
                       color: tuple[float, float, float] | str | None = None) -> dict:
        """Add an arrangement cue marker at ``beat``."""
        if not name or not name.strip():
            raise ValueError("cue marker name is required")
        params: dict = {"beat": float(beat), "name": name}
        if isinstance(color, str):
            params["color"] = color
        elif color is not None:
            r, g, b = color[:3]
            a = color[3] if len(color) > 3 else 1.0
            params.update({"r": float(r), "g": float(g), "b": float(b), "a": float(a)})
        return self.b.request("cue_marker.create", params)

    def update_scene(self, index: int, *, name: str | None = None,
                     comment: str | None = None,
                     color: tuple[float, float, float] | str | None = None) -> dict:
        """Update a launcher scene's title, comment, or color."""
        params: dict = {"index": int(index)}
        if name is not None:
            params["name"] = str(name)
        if comment is not None:
            params["comment"] = str(comment)
        if isinstance(color, str):
            params["color"] = color
        elif color is not None:
            r, g, b = color[:3]
            a = color[3] if len(color) > 3 else 1.0
            params.update({"r": float(r), "g": float(g), "b": float(b), "a": float(a)})
        return self.b.request("scene.update", params)

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

    def group_track(self, name: str, *, parent: str | JavaTrack | None = None) -> JavaTrack:
        """Append a group track at root or inside ``parent`` group."""
        if not name or not name.strip():
            raise ValueError("group track name is required")
        parent_path = parent._read_path() if isinstance(parent, JavaTrack) else parent
        params: dict = {"name": name}
        if parent_path:
            params["parent"] = str(parent_path)
        result = self.b.request("track.create_group", params)
        top_index = result["index"] if not parent_path else None
        return JavaTrack(self, top_index, name, path=result.get("path", name))

    def track(self, name: str, device: str | None = None,
              kind: str = "instrument", *,
              parent: str | JavaTrack | None = None) -> JavaTrack:
        if kind not in ("instrument", "audio", "hybrid", "group"):
            raise ValueError("track kind must be instrument, audio, hybrid, or group")
        if kind == "group":
            return self.group_track(name, parent=parent)
        if not name or not name.strip():
            raise ValueError("track name is required")
        parent_path = parent._read_path() if isinstance(parent, JavaTrack) else parent
        if parent_path:
            result = self.b.request("track.create", {
                "type": kind, "index": -1, "name": name, "parent": str(parent_path),
            })
            track = JavaTrack(self, None, name, path=result.get("path", f"{parent_path}/{name}"))
            if device is not None:
                track.add_device(device)
            return track
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

    def audio_track(self, name: str, *, parent: str | JavaTrack | None = None) -> JavaTrack:
        return self.track(name, kind="audio", parent=parent)

    def track_at(self, path: str) -> JavaTrack:
        """Select an existing track by title path, ``master``, or ``fx/Name``.

        Duplicate siblings resolve to the first. Path handles expose the
        polling readers and path-capable track mutations.
        """
        if not isinstance(path, str) or not path or any(not part for part in path.split("/")):
            raise ValueError("a nonempty track path is required")
        return JavaTrack(self, None, path.rsplit("/", 1)[-1], path=path)


class JavaTrack:
    """Index-based editing handle or path-based polling/editing handle."""

    def __init__(self, song: JavaSong, index: int | None, name: str,
                 *, path: str | None = None):
        self.s = song
        self._index = index
        self.name = name
        self.path = path

    @property
    def idx(self) -> int:
        if self._index is None:
            raise ValueError("this operation requires an index-based track handle")
        return self._index

    def _read_path(self) -> str:
        if self.path is not None:
            return self.path
        if self.idx == -1:
            return "master"
        if self.idx < -1:
            return "fx/" + self.name
        return self.snapshot()["name"]

    def _target_params(self, **extra) -> dict:
        if self._index is not None:
            return {"index": self._index, **extra}
        return {"path": self._read_path(), **extra}

    def _track_params(self, **extra) -> dict:
        if self._index is not None:
            return {"track": self._index, **extra}
        return {"path": self._read_path(), **extra}

    def read(self) -> dict:
        """Read this track subtree using the same builder as ``dump()``."""
        return self.s.b.request("track.read", {"path": self._read_path()}, timeout=125)

    def read_clip(self, index: int) -> dict:
        """Read one arranger clip, including its note or audio content."""
        return self.s.b.request("clip.read", {"path": self._read_path(), "clip": int(index)}, timeout=125)

    def read_device(self, index: int) -> dict:
        """Read identity, nested chains, and resident native/plugin parameters."""
        return self.s.b.request("device.read", {"path": self._read_path(), "device": int(index)}, timeout=125)

    def enumerate_automation(self) -> list[dict]:
        """Poll arranger lane targets, units, and point counts for this track."""
        return self.s.b.request("automation.enumerate", {"path": self._read_path()}, timeout=125)

    def snapshot(self) -> dict:
        if self._index is None:
            return self.read()
        return self.s.b.request("track.snapshot", {"index": self.idx})

    def add_device(self, name: str) -> JavaTrack:
        """Append a built-in Bitwig factory device by its exact catalog name."""
        if not name or not name.strip():
            raise ValueError("factory device name is required")
        before = self.s.b.request("device.count", self._track_params())["count"]
        after = self.s.b.request("device.insert_factory", self._track_params(name=name))["count"]
        if after != before + 1:
            raise BridgeError("device count did not increase after insertion")
        return self

    def devices(self) -> list[dict]:
        """Read device indexes, optional display names, and enabled states."""
        return self.s.b.request("device.snapshot", self._track_params())

    def enable_device(self, index: int, on: bool = True) -> JavaTrack:
        self.s.b.request("device.set_enabled", self._track_params(index=int(index), on=bool(on)))
        return self

    def rename_device(self, index: int, name: str) -> JavaTrack:
        self.s.b.request("device.rename", self._track_params(device=int(index), name=name))
        return self

    def expand_device(self, index: int, on: bool = True) -> JavaTrack:
        self.s.b.request("device.set_expanded", self._track_params(device=int(index), on=bool(on)))
        return self

    def delete_device(self, index: int) -> JavaTrack:
        before = self.s.b.request("device.count", self._track_params())["count"]
        after = self.s.b.request("device.delete", self._track_params(device=int(index)))["count"]
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
        self.s.b.request("device.set_parameters", self._track_params(
            device=int(device_index), params=values, unit=unit,
        ))
        return self

    def get_parameters(self, device_index: int, names, unit: str | None = None) -> dict:
        """Read named numeric, boolean, or enum-index parameters.

        Numbers default to normalized 0..1 values. An explicit Bitwig Unit
        (for example ``DECIBEL``) reads numbers in that unit instead.
        """
        return self.s.b.request("device.get_parameters", self._track_params(
            device=int(device_index), names=[str(n) for n in names], unit=unit,
        ))

    def list_parameters(self, device_index: int = 0) -> list[dict]:
        """List available parameter paths and kinds for a loaded native device."""
        return self.s.b.request("device.list_parameters", self._track_params(
            device=int(device_index),
        ))

    def rename(self, name: str) -> JavaTrack:
        result = self.s.b.request("track.rename", self._target_params(name=name))
        self.name = name
        if self.path is not None:
            if self._index is not None:
                self.path = "master" if self._index == -1 else (
                    "fx/" + name if self._index < -1 else name)
            elif isinstance(result, dict) and isinstance(result.get("path"), str):
                self.path = result["path"]
        return self

    def automate(self, param: str, points, device_index: int = 0,
                 unit: str | None = None) -> JavaTrack:
        """Replace an arranger lane for volume, pan, or a native parameter path.

        Points are (beat, value[, curvature, 'linear'|'hold']). Numeric values
        default to 0..1, including pan (0.5 is center). Use an explicit Bitwig
        Unit for native parameters when needed. At least two points are required.
        """
        self.s.b.request("automation.write", self._track_params(
            device=int(device_index), param=param,
            unit=unit, points=[list(point) for point in points],
        ))
        return self

    def automation(self, param: str, device_index: int = 0) -> dict:
        """Read stored beats, normalized values, curvature, and interpolation."""
        return self.s.b.request("automation.read", self._track_params(
            device=int(device_index), param=param,
        ))

    def volume(self, value: float) -> JavaTrack:
        self.s.b.request("track.set_volume", self._target_params(value=float(value)))
        return self

    def pan(self, value: float) -> JavaTrack:
        """Set pan from -1 (left) through 0 (center) to +1 (right)."""
        self.s.b.request("track.set_pan", self._target_params(value=(float(value) + 1.0) / 2.0))
        return self

    def mute(self, on: bool = True) -> JavaTrack:
        self.s.b.request("track.set_mute", self._target_params(on=bool(on)))
        return self

    def solo(self, on: bool = True) -> JavaTrack:
        self.s.b.request("track.set_solo", self._target_params(on=bool(on)))
        return self

    def comment(self, text: str) -> JavaTrack:
        self.s.b.request("track.set_comment", self._target_params(comment=str(text)))
        return self

    def active(self, on: bool = True) -> JavaTrack:
        self.s.b.request("track.set_active", self._target_params(on=bool(on)))
        return self

    def route_to(self, destination: str | JavaTrack) -> JavaTrack:
        dest_path = destination._read_path() if isinstance(destination, JavaTrack) else str(destination)
        self.s.b.request("track.set_destination", self._target_params(destination=dest_path))
        return self

    def monitor(self, mode: str = "AUTO") -> JavaTrack:
        """Set monitor mode: OFF, ON, or AUTO."""
        if mode not in ("OFF", "ON", "AUTO"):
            raise ValueError("monitor mode must be OFF, ON, or AUTO")
        self.s.b.request("track.set_monitor", self._target_params(mode=mode))
        return self

    def color(self, r: float, g: float, b: float, a: float = 1.0) -> JavaTrack:
        self.s.b.request("track.set_color", self._target_params(
            r=float(r), g=float(g), b=float(b), a=float(a),
        ))
        return self

    def send(self, send_index: int, value: float) -> JavaTrack:
        """Set a send amount from 0 to 1; sends exist per effect track."""
        self.s.b.request("track.set_send", self._target_params(
            send=int(send_index), value=float(value),
        ))
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
        self.s.b.request("clip.create_arranger_with_notes", self._track_params(
            start=float(start),
            duration=self.s.total if dur is None else float(dur),
            notes=payload,
        ))
        return self

    def clips(self, segments) -> JavaTrack:
        for start, duration, notes in segments:
            self.clip(notes, dur=duration, start=start)
        return self
