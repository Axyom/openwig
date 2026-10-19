"""Experimental Python composition surface for the Bitwig 6.1.5 Java bridge.

This covers only operations verified against a disposable project. The existing
``Song`` remains the complete controller-backed API until feature parity.
"""

from __future__ import annotations

from openwig.bridge import BridgeClient, BridgeError
from openwig.java_devices import JavaDeviceMethods


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

    def _edit_range(self, method: str, start: float, end: float) -> dict:
        import math
        start, end = float(start), float(end)
        if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start:
            raise ValueError("a finite positive arranger range is required")
        return self.b.request(method, {"start": start, "end": end}, timeout=125)

    def duplicate_range(self, start: float, end: float) -> dict:
        """Repeat a beat range, pushing later clips, automation and markers.

        Bitwig's native command owns its undo transaction and splits clips
        crossing the range boundaries. Transport loop bounds are unchanged.
        """
        return self._edit_range("project.duplicate_range", start, end)

    def insert_time(self, start: float, beats: float) -> dict:
        """Insert empty arranger time across all tracks; times are beats.

        Splits crossing clips and shifts later clips, automation and markers.
        Transport loop bounds are unchanged; set them explicitly if needed.
        """
        return self._edit_range("project.insert_time", start, float(start) + float(beats))

    def delete_range(self, start: float, end: float) -> dict:
        """Delete a beat range across all tracks and pull later material left.

        Splits crossing clips; includes effect/master automation and markers.
        Transport loop bounds are unchanged. Use a disposable project or copy.
        """
        return self._edit_range("project.delete_range", start, end)

    def project_info(self) -> dict:
        """Return the selected project's native path, directory and name."""
        return self.b.request("document.info")

    def projects(self) -> list[dict]:
        """List open projects, including their paths and root track counts."""
        return self.b.request("document.list")["documents"]

    def select_project(self, path) -> dict:
        """Select an already open native project by its absolute file path."""
        from pathlib import Path
        return self.b.request("document.select", {"path": str(Path(path).resolve())})

    def _wait_document_job(self, job: dict, timeout: float) -> dict:
        import time
        deadline = time.monotonic() + timeout
        while job.get("state") == "running":
            if time.monotonic() > deadline:
                raise BridgeError(f"document {job.get('operation')} timed out: {job.get('path')}")
            time.sleep(.25)
            job = self.b.request("document.job_status", {"job": job["job"]})
        if job.get("state") != "complete":
            raise BridgeError(f"document {job.get('operation')} failed: {job.get('error', job)}")
        return job

    def open_project(self, path, *, activate_engine: bool = False, timeout: float = 300) -> dict:
        """Open a native project in a new tab and select it; nothing is saved.

        Bitwig copies a project saved by another version to a backup next to
        the file when opening it, so open copies of projects you care about.
        """
        from pathlib import Path
        path = str(Path(path).resolve())
        self._wait_document_job(self.b.request("document.open", {
            "path": path, "activate_engine": bool(activate_engine)}, timeout=60), timeout)
        return self.select_project(path)

    def close_project(self, path, *, discard_changes: bool = False, timeout: float = 120) -> dict:
        """Close an open project tab without saving; unsaved edits are lost."""
        from pathlib import Path
        if not discard_changes:
            raise ValueError("close_project discards unsaved changes; pass discard_changes=True")
        return self._wait_document_job(self.b.request("document.close", {
            "path": str(Path(path).resolve()), "discard_changes": True}, timeout=60), timeout)

    def export_project(self, path) -> dict:
        """Capture a portable native project ZIP, with media and plugin states.

        Includes unsaved document edits. Uses Bitwig 6.1.5 native serialization;
        the original project stays open. The archive must not already exist.
        Installed plugins and licensed Bitwig packages are still required.
        """
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from openwig.project_archive import pack_project
        with TemporaryDirectory(prefix="openwig-project-") as work:
            project = Path(work) / "project" / "project.bwproject"
            saved = self.save_project(project)
            result = pack_project(project, path)
            result.update(media_files=saved["media_files"], plugin_states=saved["plugin_states"])
            return result

    @staticmethod
    def write_project(path, directory) -> dict:
        """Write an archive to a new project directory, without changing Bitwig.

        Can also be used offline via openwig.project_archive.write_project.
        Open the returned .bwproject path in Bitwig 6.1.5 to use the project.
        Existing directories are refused; every archived file hash is checked.
        """
        from openwig.project_archive import write_project
        return write_project(path, directory)

    def save_project(self, path) -> dict:
        """Write a native .bwproject copy with collected media and plugin state.

        Serializes a detached copy of the live document. The open project and
        its original file remain unchanged. The destination must not exist.
        """
        from pathlib import Path
        return self.b.request("project.save_copy", {"path": str(Path(path).resolve())}, timeout=610)

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

    def modulator_catalog(self) -> list[dict]:
        """List Bitwig's factory modulators as ``{"name", "modulator_id"}``."""
        return self.b.request("modulator.catalog")["modulators"]

    @staticmethod
    def _scene_index(index: int) -> int:
        if type(index) is not int or index < 0:
            raise ValueError("scene index must be a nonnegative integer")
        return index

    def scenes(self) -> list[dict]:
        """Read existing scene metadata without creating missing rows."""
        return self.b.request("scene.snapshot")["scenes"]

    def insert_scene(self, index: int | None = None) -> dict:
        """Insert an empty launcher row across all tracks; None appends."""
        params = {} if index is None else {"index": self._scene_index(index)}
        return self.b.request("scene.insert", params, timeout=125)

    def delete_scene(self, index: int) -> dict:
        """Delete a launcher row, its clips and its metadata across all tracks."""
        return self.b.request("scene.delete", {"index": self._scene_index(index)}, timeout=125)

    def move_scene(self, index: int, destination: int) -> dict:
        """Move a complete launcher row to its final index, preserving content."""
        return self.b.request("scene.move", {"index": self._scene_index(index),
            "destination": self._scene_index(destination)}, timeout=125)

    def update_scene(self, index: int, *, name: str | None = None,
                     comment: str | None = None,
                     color: tuple[float, float, float] | str | None = None) -> dict:
        """Update an existing scene's title, comment, or color; never creates rows."""
        params: dict = {"index": self._scene_index(index)}
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


class JavaTrack(JavaDeviceMethods):
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

    @staticmethod
    def _clip_index(index: int) -> int:
        if type(index) is not int or index < 0:
            raise ValueError("clip index must be a nonnegative integer")
        return index

    @staticmethod
    def _slot_index(slot: int) -> int:
        if type(slot) is not int or slot < 0:
            raise ValueError("slot index must be a nonnegative integer")
        return slot

    @staticmethod
    def _clip_number(value, key: str) -> float:
        import math
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError(f"{key} must be a finite number")
        return float(value)

    @classmethod
    def _validate_clip_changes(cls, changes: dict, *, launcher: bool) -> None:
        allowed = {"start", "duration", "name", "muted", "loop", "loop_start",
                   "loop_duration", "play_start_offset", "color", "fade_in", "fade_out"}
        if launcher:
            allowed -= {"start"}
        if not changes or changes.keys() - allowed:
            raise ValueError("provide supported clip changes")
        for key, value in changes.items():
            if key in {"muted", "loop"}:
                if type(value) is not bool:
                    raise ValueError(f"{key} must be boolean")
            elif key in {"name", "color"}:
                if not isinstance(value, str):
                    raise ValueError(f"{key} must be a string")
            else:
                number = cls._clip_number(value, key)
                if key in {"duration", "loop_duration"} and number <= 0:
                    raise ValueError(f"{key} must be positive")
                if key in {"start", "fade_in", "fade_out"} and number < 0:
                    raise ValueError(f"{key} must be nonnegative")

    def update_clip(self, index: int, **changes) -> dict:
        """Edit an arranger clip; times and fades are beats.

        Moving clips changes their sorted indexes. Overlapping another clip is
        rejected. Resizing trims the container, preserving its content events.
        Color is snapped to Bitwig's palette. Returns the updated clip data.
        """
        self._clip_index(index)
        self._validate_clip_changes(changes, launcher=False)
        return self.s.b.request("clip.update", self._track_params(clip=index, changes=changes))

    def delete_clip(self, index: int) -> dict:
        """Delete an arranger clip. Later clips keep their positions."""
        return self.s.b.request("clip.delete", self._track_params(clip=self._clip_index(index)))

    def copy_clip(self, index: int, start: float, *, destination: JavaTrack | None = None) -> dict:
        """Copy a complete arranger clip to a beat position, without linked content.

        Defaults to this track. Returns the destination's sorted ``clip`` index
        and ``data``. Overlaps are rejected. Native settings and expressions are
        preserved; subsequent content edits do not affect the original.
        """
        self._clip_index(index)
        start = self._clip_number(start, "start")
        if start < 0:
            raise ValueError("start must be nonnegative")
        params = self._track_params(clip=index, start=start)
        if destination is not None:
            if not isinstance(destination, JavaTrack) or destination.s is not self.s:
                raise ValueError("destination must be a track in the same JavaSong")
            params["destination"] = destination._track_params()
        return self.s.b.request("clip.copy", params, timeout=125)

    def split_clip(self, index: int, beat: float) -> dict:
        """Split strictly inside an arranger clip at an absolute beat position.

        Returns ``clips`` containing each piece's sorted index and data. Bitwig
        preserves content, adjusts play offsets, and removes the internal fades.
        """
        self._clip_index(index)
        beat = self._clip_number(beat, "beat")
        if beat < 0:
            raise ValueError("beat must be nonnegative")
        return self.s.b.request("clip.split", self._track_params(clip=index, beat=beat), timeout=125)

    _conditions = {"chance", "chance_enabled", "occurrence", "occurrence_enabled",
                   "recurrence", "recurrence_enabled", "repeat_count", "repeat_curve", "repeat_enabled"}

    @classmethod
    def _validate_conditions(cls, data):
        for key in cls._conditions & data.keys():
            value = data[key]
            if key.endswith("_enabled"):
                if type(value) is not bool:
                    raise ValueError(f"{key} must be boolean")
            elif key == "occurrence":
                if not isinstance(value, str) or value not in {"ALWAYS", "FIRST", "NOT_FIRST", "PREV", "NOT_PREV", "PREV_KEY",
                                 "NOT_PREV_KEY", "PREV_CHANNEL", "NOT_PREV_CHANNEL", "FILL", "NOT_FILL"}:
                    raise ValueError("unsupported occurrence")
            elif key in {"recurrence", "repeat_count"}:
                low, high = (0, 2047) if key == "recurrence" else (-127, 127)
                if type(value) is not int or not low <= value <= high:
                    raise ValueError(f"{key} must be an integer in {low}..{high}")
            else:
                low = 0 if key == "chance" else -1
                if not low <= cls._clip_number(value, key) <= 1:
                    raise ValueError(f"{key} must be in {low}..1")

    @classmethod
    def _validate_expressions(cls, expressions, *, audio=False):
        names = {"gain", "pan", "transpose"}
        if audio:
            names.add("formant")
        else:
            names |= {"timbre", "pressure"}
        if not isinstance(expressions, dict) or expressions.keys() - names:
            raise ValueError("unsupported expressions")
        for curve in expressions.values():
            if not isinstance(curve, dict) or curve.keys() != {"unit", "points"} or not isinstance(curve["unit"], str):
                raise ValueError("expression requires unit and points")
            if not isinstance(curve["points"], (list, tuple)) or len(curve["points"]) > 4096:
                raise ValueError("expression needs at most 4096 points")
            last = -1.
            for point in curve["points"]:
                if not isinstance(point, (list, tuple)) or not 2 <= len(point) <= 4:
                    raise ValueError("expression point needs beat, value, optional curvature/interpolation")
                beat = cls._clip_number(point[0], "expression beat")
                cls._clip_number(point[1], "expression value")
                if beat < 0 or beat <= last:
                    raise ValueError("expression beats must be nonnegative and increasing")
                if len(point) > 2 and not -1 <= cls._clip_number(point[2], "curvature") <= 1:
                    raise ValueError("curvature must be in -1..1")
                if len(point) > 3 and point[3] not in {"linear", "hold"}:
                    raise ValueError("interpolation must be linear or hold")
                last = beat

    @classmethod
    def _note_payload(cls, notes) -> list:
        import copy
        payload = copy.deepcopy(list(notes))
        allowed = {"key", "channel", "start", "duration", "on_velocity",
                   "off_velocity", "muted", "expressions", "velocity_spread", "content_start"} | cls._conditions
        for note in payload:
            if not isinstance(note, dict) or note.keys() - allowed or not {"key", "duration"} <= note.keys():
                raise ValueError("notes require key/duration and supported fields")
            for key, limit in (("key", 127), ("channel", 15)):
                value = note.get(key, 0)
                if type(value) is not int or not 0 <= value <= limit:
                    raise ValueError(f"{key} must be an integer in 0..{limit}")
            duration = cls._clip_number(note["duration"], "duration")
            start = cls._clip_number(note.get("start", 0), "start")
            if duration <= 0 or start < 0:
                raise ValueError("note start must be nonnegative and duration positive")
            for key in ("on_velocity", "off_velocity", "velocity_spread"):
                if key in note and not 0 <= cls._clip_number(note[key], key) <= 1:
                    raise ValueError(f"{key} must be in 0..1")
            if "muted" in note and type(note["muted"]) is not bool:
                raise ValueError("muted must be boolean")
            if "content_start" in note and cls._clip_number(note["content_start"], "content_start") < 0:
                raise ValueError("content_start must be nonnegative")
            cls._validate_conditions(note)
            cls._validate_expressions(note.get("expressions", {}))
        return payload

    def replace_notes(self, index: int, notes) -> dict:
        """Replace a MIDI clip's content with note dictionaries; [] clears it.

        Required fields: ``key`` (0..127), ``duration`` (positive beats).
        Optional: ``channel`` (0..15), ``start`` (local beats), ``on_velocity``,
        ``off_velocity``, ``muted``, ``chance``. Defaults: 0, 0, .8, .5, False, 1.
        Optional ``expressions`` maps gain/pan/transpose/timbre/pressure to
        ``{"unit": BitwigUnitName, "points": [[content_beat, value, curvature?, interpolation?], ...]}``.
        Empty points clear a curve. Points increase in content beats. Replacement
        unlinks shared content and preserves the clip container's settings.
        """
        self._clip_index(index)
        payload = self._note_payload(notes)
        return self.s.b.request("clip.replace_notes", self._track_params(clip=index, notes=payload), timeout=125)

    def import_audio(self, file, *, start: float = 0, duration: float | None = None,
                     source_offset: float = 0, gain_db: float = 0,
                     name: str | None = None) -> dict:
        """Import local audio; return its sorted ``clip`` index and ``data``.

        Start/duration are beats; source_offset is seconds. Default duration
        follows the source remainder and the project's tempo map. Uses RAW audio.
        Overlaps are rejected. Requires an audio-capable track.
        """
        params = self._audio_source_params(file, start, duration, source_offset, gain_db)
        if name is not None:
            if not isinstance(name, str):
                raise ValueError("name must be a string")
            params["name"] = name
        return self.s.b.request("clip.import_audio", self._track_params(**params), timeout=125)

    @classmethod
    def _clip_or_slot(cls, clip, slot) -> dict:
        if (clip is None) == (slot is None):
            raise ValueError("address exactly one clip index or launcher slot")
        return {"clip": cls._clip_index(clip)} if slot is None else {"slot": cls._slot_index(slot)}

    def import_note(self, clip: int | None = None, *, data: dict, slot: int | None = None) -> dict:
        """Add a MIDI note dictionary, addressed by arranger clip or launcher slot."""
        target = self._clip_or_slot(clip, slot)
        payload = self._note_payload([data])[0]
        return self.s.b.request("note.import", self._track_params(**target, data=payload), timeout=125)

    def update_note(self, clip: int | None = None, index: int = 0, **changes) -> dict:
        """Edit one note, preserving its other properties and expression curves.

        Supports replace_notes fields, plus velocity_spread (0..1), content_start
        (beats), chance/occurrence/recurrence/repeat controls and their enabled
        flags. Recurrence is Bitwig's packed 11-bit mask: period minus one in
        bits 0..2, enabled iterations in bits 3..10. Repeat count is -127..127;
        repeat_curve is -1..1. Returns the updated sorted note index and data.
        """
        target = self._clip_or_slot(clip, changes.pop("slot", None))
        if not changes:
            raise ValueError("provide note changes")
        self._note_payload([{"key": 0, "duration": 1, **changes}])
        return self.s.b.request("note.update", self._track_params(
            **target, note=self._clip_index(index), changes=changes), timeout=125)

    def delete_note(self, clip: int | None = None, index: int = 0, *, slot: int | None = None) -> dict:
        """Delete one MIDI note, retaining the clip and its remaining notes."""
        target = self._clip_or_slot(clip, slot)
        return self.s.b.request("note.delete", self._track_params(
            **target, note=self._clip_index(index)), timeout=125)

    def import_audio_event(self, clip: int | None = None, file=None, *, start: float = 0,
                           duration: float | None = None, source_offset: float = 0,
                           gain_db: float = 0, slot: int | None = None) -> dict:
        """Add RAW audio to an existing audio clip; returns event index and data.

        Address an arranger clip by ``clip`` index or a launcher clip by
        ``slot`` index. Start/duration are local content beats, source_offset
        is seconds. Default duration follows source remainder at the first
        arranger position (clip start + local start - clip play offset);
        launcher clips always start at zero. Overlaps are rejected; the
        container is not automatically extended. Content becomes independent.
        """
        target = self._clip_or_slot(clip, slot)
        params = self._audio_source_params(file, start, duration, source_offset, gain_db)
        return self.s.b.request("audio_event.import", self._track_params(**target, **params), timeout=125)

    def update_audio_event(self, clip: int | None = None, index: int = 0, **changes) -> dict:
        """Edit an audio event; returns its sorted index and updated data.

        Address an arranger clip by ``clip`` index or a launcher clip by
        ``slot`` keyword. Fields: start/duration/fade_in/fade_out (local
        beats), source_offset (seconds), gain_db, muted, reverse. Source
        offsets work through forward/reversed warp mappings; RAW offsets require
        forward audio. Change offsets and reverse in separate calls. Moves reject overlaps and can change event
        indexes. Other clips retain their content. Container duration is
        unchanged.

        Processing fields: warp_mode, warp_markers ([[beat, source_seconds],
        ...]), content_offset (beats), transpose/formant (semitones), pan (-1..1),
        preserve_formants, preview_onsets, preserve_transients_mode,
        transient_rate, warp_grain_size, warp_tail_mode. RAW-to-warp changes
        require explicit markers. Transpose requires a pitch-processing mode.
        Immediate stretch, REPITCH and semitone changes are live-verified.
        Formant envelope resolution accepts integers in 8..512.
        Expressions maps gain/pan/transpose/formant to unit/points dictionaries;
        points use content beats even in RAW mode, with optional curvature and
        linear/hold interpolation. Empty points clear that curve. Probability
        and repeat fields match update_note. Unspecified curves are retained.
        """
        target = self._clip_or_slot(clip, changes.pop("slot", None))
        self._clip_index(index)
        enums = {
            "warp_mode": {"RAW", "STRETCH", "STRETCH_HD", "REPITCH", "SLICE", "CYCLIC",
                          "ELASTIQUE_PRO", "ELASTIQUE", "ELASTIQUE_ECO", "ELASTIQUE_SOLO"},
            "preserve_transients_mode": {"OFF", "SOFT", "HARD"},
            "transient_rate": {"ONSETS", "THIRTYSECOND", "SIXTEENTH", "EIGHT", "QUARTER"},
            "warp_grain_size": {"SMALL", "NORMAL", "LARGE"},
            "warp_tail_mode": {"NONE", "GRAIN", "PINGPONG"},
        }
        allowed = {"start", "duration", "fade_in", "fade_out", "source_offset", "gain_db", "muted", "reverse",
                   "content_offset", "warp_markers", "transpose", "formant", "pan", "preserve_formants", "preview_onsets", "formant_envelope_resolution", "expressions"} | enums.keys() | self._conditions
        if not changes or changes.keys() - allowed:
            raise ValueError("provide supported audio event changes")
        for key, value in changes.items():
            if key in self._conditions:
                self._validate_conditions({key: value})
            elif key == "expressions":
                self._validate_expressions(value, audio=True)
            elif key in enums:
                if not isinstance(value, str) or value not in enums[key]:
                    raise ValueError(f"unsupported {key}")
            elif key == "formant_envelope_resolution":
                if type(value) is not int or not 8 <= value <= 512:
                    raise ValueError("formant_envelope_resolution must be an integer in 8..512")
            elif key == "warp_markers":
                if not isinstance(value, (list, tuple)) or len(value) < 2:
                    raise ValueError("at least two warp markers required")
                last_beat = last_second = -1.
                for point in value:
                    if not isinstance(point, (list, tuple)) or len(point) != 2:
                        raise ValueError("warp markers need beat and source seconds")
                    beat, second = (self._clip_number(v, "warp marker") for v in point)
                    if beat < 0 or second < 0 or beat <= last_beat or second <= last_second:
                        raise ValueError("warp marker beats and seconds must increase")
                    last_beat, last_second = beat, second
            elif key in {"muted", "reverse", "preserve_formants", "preview_onsets"}:
                if type(value) is not bool:
                    raise ValueError(f"{key} must be boolean")
            else:
                number = self._clip_number(value, key)
                if key == "duration" and number <= 0:
                    raise ValueError("duration must be positive")
                if key in {"start", "source_offset", "content_offset", "fade_in", "fade_out"} and number < 0:
                    raise ValueError(f"{key} must be nonnegative")
                if key == "pan" and not -1 <= number <= 1:
                    raise ValueError("pan must be in -1..1")
                if key in {"transpose", "formant"} and not -96 <= number <= 96:
                    raise ValueError(f"{key} must be in -96..96 semitones")
        if "source_offset" in changes and "content_offset" in changes:
            raise ValueError("address the source by either beats or seconds")
        if "source_offset" in changes and "reverse" in changes:
            raise ValueError("set source offset while forward, then reverse separately")
        return self.s.b.request("audio_event.update", self._track_params(**target, event=index, changes=changes), timeout=125)

    def delete_audio_event(self, clip: int | None = None, index: int = 0, *, slot: int | None = None) -> dict:
        """Remove exactly one event; keep other events and the clip container.

        Address an arranger clip by ``clip`` index or a launcher clip by ``slot``.
        """
        target = self._clip_or_slot(clip, slot)
        return self.s.b.request("audio_event.delete", self._track_params(
            **target, event=self._clip_index(index)), timeout=125)

    @staticmethod
    def _launcher_flags(has_stop, replace: bool = False) -> dict:
        flags = {}
        if has_stop is not None:
            if type(has_stop) is not bool:
                raise ValueError("has_stop must be boolean")
            flags["has_stop"] = has_stop
        if type(replace) is not bool:
            raise ValueError("replace must be boolean")
        if replace:
            flags["replace"] = True
        return flags

    def read_launcher_slot(self, slot: int) -> dict:
        """Read one launcher slot: stop button, clip data, playing/queued."""
        return self.s.b.request("launcher.read", self._track_params(slot=self._slot_index(slot)))

    def put_launcher_notes(self, slot: int, notes, duration: float, *,
                           name: str = "", loop: bool = True,
                           has_stop: bool | None = None, replace: bool = False) -> dict:
        """Author a MIDI clip in a launcher slot; notes use replace_notes fields.

        The clip spans ``duration`` beats and loops its full length unless
        ``loop`` is false. Occupied slots need ``replace=True``. Slots are
        created on demand within the project's scene bank. Returns the slot
        snapshot including the clip and its launch settings.
        """
        if type(loop) is not bool:
            raise ValueError("loop must be boolean")
        if not isinstance(name, str):
            raise ValueError("name must be a string")
        duration = self._clip_number(duration, "duration")
        if duration <= 0:
            raise ValueError("duration must be positive")
        params = self._track_params(slot=self._slot_index(slot), notes=self._note_payload(notes),
                                    duration=duration, loop=loop, **self._launcher_flags(has_stop, replace))
        if name:
            params["name"] = name
        return self.s.b.request("launcher.put_notes", params, timeout=125)

    def import_launcher_audio(self, slot: int, file, *,
                              duration: float | None = None, source_offset: float = 0,
                              gain_db: float = 0, name: str | None = None,
                              loop: bool = True, has_stop: bool | None = None,
                              replace: bool = False) -> dict:
        """Author an audio clip in a launcher slot from a local RAW file.

        The clip spans the event and loops its full length unless ``loop``
        is false. Occupied slots need ``replace=True``. Returns the slot
        snapshot including the clip and its launch settings.
        """
        if type(loop) is not bool:
            raise ValueError("loop must be boolean")
        if name is not None and not isinstance(name, str):
            raise ValueError("name must be a string")
        params = self._audio_source_params(file, 0, duration, source_offset, gain_db)
        params.update(self._track_params(slot=self._slot_index(slot), loop=loop,
                                         **self._launcher_flags(has_stop, replace)))
        if name is not None:
            params["name"] = name
        return self.s.b.request("launcher.import_audio", params, timeout=125)

    def copy_arranger_to_launcher(self, slot: int, clip: int, *,
                                  has_stop: bool | None = None, replace: bool = False) -> dict:
        """Copy an arranger clip of this track into a launcher slot.

        Native settings and content are preserved and made independent.
        Occupied slots need ``replace=True``. Returns the slot snapshot.
        """
        return self.s.b.request("launcher.copy_arranger", self._track_params(
            slot=self._slot_index(slot), clip=self._clip_index(clip),
            **self._launcher_flags(has_stop, replace)), timeout=125)

    def clear_launcher_slot(self, slot: int, *, has_stop: bool | None = None) -> dict:
        """Remove a slot's clip (and optionally set its stop button).

        Uses Bitwig's native nullable slot setter; the public API wrapper
        rejects clearing. Returns the slot snapshot.
        """
        return self.s.b.request("launcher.clear", self._track_params(
            slot=self._slot_index(slot), **self._launcher_flags(has_stop)), timeout=125)

    def update_launcher_clip(self, slot: int, **changes) -> dict:
        """Edit a launcher clip's container; same fields as update_clip.

        Launcher clips have no arranger start; ``duration`` resizes the clip.
        Content edits use the audio-event methods with ``slot=`` or
        replace-notes staging. Returns the updated clip data.
        """
        self._slot_index(slot)
        self._validate_clip_changes(changes, launcher=True)
        return self.s.b.request("clip.update", self._track_params(slot=slot, changes=changes), timeout=125)

    def delete_launcher_clip(self, slot: int) -> dict:
        """Remove a launcher clip, keeping the slot and its stop button."""
        return self.s.b.request("clip.delete", self._track_params(slot=self._slot_index(slot)), timeout=125)

    def update_launch_settings(self, slot: int, **changes) -> dict:
        """Edit a launcher clip's launch settings; returns the slot snapshot.

        ``launch_quantization``: DEFAULT, OFF, BARS_8, BARS_4, BARS_2, BAR,
        HALF, QUARTER, EIGHT, SIXTEENTH. ``launch_mode``: DEFAULT, FROM_START,
        CONTINUE_OR_FROM_START, CONTINUE_OR_SYNCED, SYNCED. ``next_action``:
        STOP, RETURN_TO_ARRANGEMENT, RETURN_TO_PREVIOUS_CLIP, PLAY_NEXT,
        PLAY_PREVIOUS, PLAY_FIRST, PLAY_LAST, PLAY_RANDOM, PLAY_OTHER,
        ROUND_ROBIN plus their ``_IN_BLOCK``/``_IN_PREVIOUS_BLOCK``/
        ``_IN_NEXT_BLOCK``/``_IN_OTHER_BLOCK`` variants where offered.
        ``next_action_time`` is nonnegative beats; the two booleans are
        ``use_loop_start_as_quantization_reference`` and ``has_stop``.
        """
        self._slot_index(slot)
        enums = {
            "launch_quantization": {"DEFAULT", "OFF", "BARS_8", "BARS_4", "BARS_2", "BAR",
                                    "HALF", "QUARTER", "EIGHT", "SIXTEENTH"},
            "launch_mode": {"DEFAULT", "FROM_START", "CONTINUE_OR_FROM_START",
                            "CONTINUE_OR_SYNCED", "SYNCED"},
            "next_action": {"STOP", "RETURN_TO_ARRANGEMENT", "RETURN_TO_PREVIOUS_CLIP",
                            "PLAY_NEXT", "PLAY_PREVIOUS", "PLAY_FIRST", "PLAY_LAST",
                            "PLAY_RANDOM", "PLAY_OTHER", "ROUND_ROBIN",
                            "PLAY_FIRST_IN_BLOCK", "PLAY_LAST_IN_BLOCK", "PLAY_RANDOM_IN_BLOCK",
                            "PLAY_OTHER_IN_BLOCK", "ROUND_ROBIN_IN_BLOCK",
                            "PLAY_FIRST_IN_PREVIOUS_BLOCK", "PLAY_RANDOM_IN_PREVIOUS_BLOCK",
                            "PLAY_FIRST_IN_NEXT_BLOCK", "PLAY_RANDOM_IN_NEXT_BLOCK",
                            "PLAY_FIRST_IN_OTHER_BLOCK", "PLAY_RANDOM_IN_OTHER_BLOCK"},
        }
        allowed = set(enums) | {"next_action_time", "use_loop_start_as_quantization_reference", "has_stop"}
        if not changes or changes.keys() - allowed:
            raise ValueError("provide supported launch settings")
        for key, value in changes.items():
            if key in enums:
                if not isinstance(value, str) or value not in enums[key]:
                    raise ValueError(f"unsupported {key}")
            elif key == "next_action_time":
                if self._clip_number(value, key) < 0:
                    raise ValueError("next_action_time must be nonnegative")
            elif type(value) is not bool:
                raise ValueError(f"{key} must be boolean")
        return self.s.b.request("launcher.settings", self._track_params(slot=slot, changes=changes), timeout=125)

    def launch_slot(self, slot: int) -> dict:
        """Trigger a launcher slot like Bitwig's own launch button.

        Honors the clip's launch quantization; empty slots with a stop button
        stop the track. Returns the slot snapshot after the request.
        """
        return self.s.b.request("launcher.launch", self._track_params(slot=self._slot_index(slot)))

    def stop_launcher(self, *, immediate: bool = False) -> dict:
        """Stop this track's launcher playback, all slots at once.

        ``immediate`` ignores the track's stop quantization. Transport and
        other tracks are untouched.
        """
        if type(immediate) is not bool:
            raise ValueError("immediate must be boolean")
        return self.s.b.request("launcher.stop", self._track_params(immediate=immediate))

    def return_to_arranger(self) -> dict:
        """End launcher playback; the track resumes its arranger clips."""
        return self.s.b.request("launcher.return_to_arranger", self._track_params())

    @classmethod
    def _audio_source_params(cls, file, start, duration, source_offset, gain_db):
        from pathlib import Path
        source = Path(file).resolve()
        if not source.is_file():
            raise ValueError(f"audio file does not exist: {source}")
        params = dict(file=str(source), start=start, source_offset=source_offset,
                      gain_db=gain_db)
        if duration is not None:
            params["duration"] = duration
        for key in ("start", "source_offset", "gain_db", "duration"):
            if key not in params:
                continue
            number = cls._clip_number(params[key], key)
            if key in {"start", "source_offset"} and number < 0:
                raise ValueError(f"{key} must be nonnegative")
            if key == "duration" and number <= 0:
                raise ValueError("duration must be positive")
        return params

    def read(self) -> dict:
        """Read this track subtree using the same builder as ``dump()``."""
        return self.s.b.request("track.read", {"path": self._read_path()}, timeout=125)

    def read_clip(self, index: int) -> dict:
        """Read one arranger clip, including its note or audio content."""
        return self.s.b.request("clip.read", {"path": self._read_path(), "clip": int(index)}, timeout=125)

    def enumerate_automation(self) -> list[dict]:
        """Poll arranger lane targets, units, and point counts for this track."""
        return self.s.b.request("automation.enumerate", {"path": self._read_path()}, timeout=125)

    def snapshot(self) -> dict:
        if self._index is None:
            return self.read()
        return self.s.b.request("track.snapshot", {"index": self.idx})

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
        Hold segments have zero curvature, matching native save/load behavior.
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
