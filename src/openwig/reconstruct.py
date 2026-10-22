"""Rebuild a project from a reader dump through native editing operations.

The input is the dictionary returned by ``JavaSong.dump()``. Every field is
either written through a native operation or reported: nothing is dropped
silently. Plugin states and media are resolved through caller-supplied
locations, so a dump taken on one machine can be rebuilt on another.

The rebuild targets the selected, empty project. Tracks are created with
temporary unique titles so duplicate sibling names stay addressable; the
final step restores the real titles.
"""
from __future__ import annotations

import math
import re
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from openwig.bridge import BridgeError
from openwig.java_devices import JavaDeviceChain
from openwig.java_song import JavaSong, JavaTrack

TRACK_KINDS = {"INSTRUMENT": "instrument", "AUDIO": "audio", "HYBRID": "hybrid"}
_ENUM_INDEX = re.compile(r"value=(-?\d+)\]$")


@dataclass
class Report:
    """What was written, what the writer cannot express yet, and what failed."""

    applied: Counter = field(default_factory=Counter)
    unsupported: Counter = field(default_factory=Counter)
    examples: dict = field(default_factory=dict)
    failed: list = field(default_factory=list)
    seconds: dict = field(default_factory=dict)

    def ok(self, what: str, n: int = 1) -> None:
        self.applied[what] += n

    def skip(self, what: str, where: str, detail=None) -> None:
        self.unsupported[what] += 1
        self.examples.setdefault(what, {"where": where, "detail": detail})

    def fail(self, what: str, where: str, error) -> None:
        self.failed.append({"what": what, "where": where, "error": str(error)[:3000]})

    def summary(self) -> dict:
        kinds = Counter()
        for item in self.failed:
            error = item["error"]
            cause = error[error.find("Exception: ") + 11:] if "Exception: " in error else error
            kinds[f"{item['what']}: {cause[:90]}"] += 1
        return {"applied": dict(self.applied), "unsupported": dict(self.unsupported),
                "unsupported_examples": self.examples, "failed": len(self.failed),
                "failed_by_kind": dict(kinds.most_common()), "failed_first": self.failed[:40],
                "failed_all": self.failed,
                "seconds": self.seconds}


def _rgba(color: str):
    """'#rrggbb' or '#rrggbbaa' -> (r, g, b, a) in 0..1; None for transparent."""
    if not isinstance(color, str) or not color.startswith("#") or len(color) not in (7, 9):
        return None
    values = [int(color[i:i + 2], 16) / 255 for i in range(1, len(color), 2)]
    if len(values) == 4 and values[3] == 0:
        return None
    return tuple(values) if len(values) == 4 else (*values, 1.0)


def _enum_index(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        found = _ENUM_INDEX.search(value)
        return int(found.group(1)) if found else None
    return None


def _modulator_user_name(modulator: dict) -> str:
    """The name a user gave a modulator ('' when it shows its automatic title).

    Bitwig derives the displayed ``device_name`` from ``user_name``, or numbers
    the definition's name among equal modulators ("Macro 3"). Dumps made before
    the reader reported ``user_name`` are read from the title itself.
    """
    if "user_name" in modulator:
        return modulator["user_name"] or ""
    title, base = modulator.get("device_name") or "", modulator.get("display_name") or ""
    if title == base or re.fullmatch(re.escape(base) + r" \d+", title):
        return ""
    return title


def _walk_tracks(tracks, parent=None):
    for track in tracks:
        yield track, parent
        yield from _walk_tracks(track.get("children") or [], track)


class Reconstructor:
    """Replay one reader dump into the selected project.

    ``states`` is a directory holding the plugin state files named by the
    dump (``state_path``/``state_file`` basenames). ``media`` maps an audio
    file path from the dump to a local file (identity by default). ``only``
    limits the rebuild to some dump track paths (their ancestors are kept),
    which is useful for quick partial runs.
    """

    def __init__(self, song: JavaSong, dump: dict, *, states=None, media=None,
                 only=None, log=None, settle: float = 0.0):
        self.s = song
        self.dump = dump
        self.states = Path(states) if states else None
        self.media = media or (lambda path: Path(path))
        self.log = log or (lambda message: None)
        self.settle = settle
        self.report = Report()
        self.paths: dict[int, str] = {}       # id(dump track) -> current path
        self.titles: dict[int, str] = {}      # id(dump track) -> real title
        self.by_dump_path: dict[str, dict] = {}
        self.only = self._expand(only) if only else None
        self._serial = 0
        self._pending_bindings = []
        self._pending_labels = []
        # Deactivated tracks and devices unload their plugins, whose parameters mappings and
        # automation need: deactivate them last.
        self._deactivations = []
        self._pending_sidechains = []

    # Entry point -------------------------------------------------------------

    def run(self) -> Report:
        # A running engine drops parameter writes that follow an insertion too
        # closely (measured 250 ms); with the engine off only the document counts.
        try:
            audio = self.s.b.request("document.audio_status")
            self._pacing = .25 if audio.get("connected") or audio.get("ready") else 0.0
        except Exception:
            self._pacing = .25
        steps = [("transport", self.transport), ("effect_tracks", self.effect_tracks),
                 ("main_tracks", self.main_tracks), ("track_settings", self.track_settings),
                 ("devices", self.devices), ("bindings", self.bindings),
                 ("sidechains", self.sidechains), ("scenes", self.scenes),
                 ("clips", self.clips), ("launcher", self.launcher),
                 ("automation", self.automation), ("markers", self.markers),
                 ("deactivate", self.deactivate), ("titles", self.restore_titles)]
        for name, step in steps:
            started = time.monotonic()
            self.log(f"step {name}")
            try:
                step()
            except Exception as error:  # a broken step must not hide the others
                self.report.fail("step", name, repr(error))
            self.report.seconds[name] = round(time.monotonic() - started, 1)
        return self.report

    # Selection ---------------------------------------------------------------

    def _expand(self, only):
        keep = set()
        for path in only:
            parts = path.split("/")
            for n in range(1, len(parts) + 1):
                keep.add("/".join(parts[:n]))
        return keep

    def _wanted(self, track) -> bool:
        if self.only is None:
            return True
        path = track.get("path", "")
        return path in self.only or any(path.startswith(p + "/") for p in self.only)

    def _temp_title(self, title: str) -> str:
        self._serial += 1
        return f"{title} #rw{self._serial}"

    def _handle(self, track) -> JavaTrack:
        path = self.paths[id(track)]
        return JavaTrack(self.s, None, path.rsplit("/", 1)[-1], path=path)

    def _all_tracks(self):
        """(dump track, scope) for every rebuilt track, master last."""
        for track, _ in _walk_tracks(self.dump.get("main_tracks", [])):
            if id(track) in self.paths:
                yield track, "main"
        for track in self.dump.get("effect_tracks", []):
            if id(track) in self.paths:
                yield track, "fx"
        master = self.dump.get("master")
        if master is not None and id(master) in self.paths:
            yield master, "master"

    # Project -----------------------------------------------------------------

    def transport(self):
        transport = self.dump.get("transport") or {}
        if "tempo" in transport:
            self.s.tempo(float(transport["tempo"]))
            self.report.ok("tempo")
        signature = transport.get("time_signature") or {}
        if signature and (signature.get("numerator"), signature.get("denominator")) != (4, 4):
            self._try("time_signature", "transport", lambda: self.s.b.request("transport.set_time_signature", {
                "numerator": int(signature["numerator"]), "denominator": int(signature["denominator"])}))
        groove = {}
        if "groove_enabled" in transport:
            groove["enabled"] = bool(transport["groove_enabled"])
        if "shuffle" in transport:
            groove["shuffle"] = float(transport["shuffle"])
        if groove:
            self._try("groove", "transport", lambda: self.s.b.request("transport.set_groove", groove))
        metadata = {k: v for k, v in (self.dump.get("metadata") or {}).items() if v}
        if metadata:
            try:
                self.s.set_metadata(**metadata)
                self.report.ok("metadata")
            except Exception as error:
                self.report.fail("metadata", "project", error)

    def effect_tracks(self):
        for track in self.dump.get("effect_tracks", []):
            title = self._temp_title(track["name"])
            self.s.effect_track(title)
            self.paths[id(track)] = "fx/" + title
            self.titles[id(track)] = track["name"]
            self.report.ok("effect_track")
        master = self.dump.get("master")
        if master is not None:
            self.paths[id(master)] = "master"

    def main_tracks(self):
        for track, parent in _walk_tracks(self.dump.get("main_tracks", [])):
            if not self._wanted(track):
                continue
            parent_path = self.paths.get(id(parent)) if parent is not None else None
            if parent is not None and parent_path is None:
                continue
            title = self._temp_title(track["name"])
            try:
                if track.get("is_group"):
                    created = self.s.group_track(title, parent=parent_path)
                else:
                    kind = TRACK_KINDS.get(track.get("track_type"))
                    if kind is None:
                        self.report.skip("track_type", track.get("path"), track.get("track_type"))
                        kind = "hybrid"
                    created = self.s.track(title, kind=kind, parent=parent_path)
                path = created.path or (f"{parent_path}/{title}" if parent_path else title)
                self.paths[id(track)] = path
                self.titles[id(track)] = track["name"]
                self.by_dump_path.setdefault(track.get("path"), track)
                self.report.ok("group" if track.get("is_group") else "track")
            except Exception as error:
                self.report.fail("track", track.get("path"), error)

    def track_settings(self):
        fx_names = [t["name"] for t in self.dump.get("effect_tracks", [])]
        for track, scope in self._all_tracks():
            where = track.get("path") or scope
            handle = self._handle(track)
            if scope != "master":
                rgba = _rgba(track.get("color"))
                if rgba is not None:
                    self._try("color", where, lambda: handle.color(*rgba))
                if track.get("comment"):
                    self._try("comment", where, lambda: handle.comment(track["comment"]))
                if track.get("mute"):
                    self._try("mute", where, lambda: handle.mute(True))
                if track.get("solo"):
                    self._try("solo", where, lambda: handle.solo(True))
                if track.get("is_active") is False:
                    self._deactivations.append(("active", where, lambda handle=handle: handle.active(False)))
            if "volume" in track:
                self._try("volume", where, lambda: self.s.b.request(
                    "track.set_volume", handle._target_params(value=float(track["volume"]))))
            if "pan" in track:
                self._try("pan", where, lambda: self.s.b.request(
                    "track.set_pan", handle._target_params(value=float(track["pan"]))))
            if track.get("crossfade") not in (None, 1):
                self.report.skip("crossfade", where, track.get("crossfade"))
            self._input_source(handle, track, scope, where)
            destination = (track.get("destination") or {}).get("path")
            if scope in ("main", "fx") and destination:
                # Tracks created inside a group still default to the master output.
                target = self._resolve_path(destination)
                if target is None:
                    self.report.skip("destination", where, destination)
                elif destination != "master" or "/" in where or scope == "fx":
                    self._try("destination", where, lambda: handle.route_to(target))
            for send in track.get("sends") or []:
                amount = send.get("amount", 0)
                if amount:
                    index = fx_names.index(send["target"]) if send.get("target") in fx_names else send.get("index")
                    self._try("send", where, lambda: handle.send(int(index), float(amount)))
                if send.get("mode") not in (None, "AUTO"):
                    self.report.skip("send_mode", where, send.get("mode"))

    def _source_spec(self, source):
        """Reader source {path, tap} -> rebuilt spec, None for 'no input', False if unresolvable."""
        if source is None:
            return None
        if not isinstance(source, dict) or "path" not in source:
            return False
        path = self._resolve_path(source["path"])
        if path is None:
            return False
        return {"path": path, "tap": source.get("tap")}

    def _input_source(self, handle, track, scope, where):
        if scope != "main":
            return
        source = track.get("source")
        spec = self._source_spec(source)
        if spec is False:
            self.report.skip("input_source", where, source)
        elif spec is not None or source is None:
            # New tracks can default to "All ins"; the reader reports no input as null.
            self._try("input_source", where, lambda: self.s.b.request(
                "track.set_source", handle._target_params(source=spec)))

    def _resolve_path(self, dump_path: str):
        """Map a dump track path (title-based) to the rebuilt track's current path."""
        if dump_path == "master":
            return "master"
        if dump_path.startswith("fx/"):
            name = dump_path[3:]
            for track in self.dump.get("effect_tracks", []):
                if track["name"] == name and id(track) in self.paths:
                    return self.paths[id(track)]
            return None
        track = self.by_dump_path.get(dump_path)
        return self.paths.get(id(track)) if track is not None else None

    def _try(self, what, where, action):
        try:
            action()
            self.report.ok(what)
            return True
        except Exception as error:
            self.report.fail(what, where, error)
            return False

    # Devices -----------------------------------------------------------------

    def devices(self):
        for track, scope in self._all_tracks():
            devices = track.get("devices") or []
            if devices:
                self._chain(self._handle(track), devices, track.get("path") or scope)

    def _chain(self, handle, devices, where):
        created = []
        for position, device in enumerate(devices):
            index = self._device(handle, position, device, where)
            created.append((index, device))
        for index, device in created:
            if index is None:
                continue
            self._device_details(handle, index, device, where)

    def _device(self, handle, position, device, where):
        kind = device.get("kind")
        place = f"{where}#{position}:{device.get('device_name')}"
        try:
            if kind == "native":
                before = self.s.b.request("device.count", handle._track_params())["count"]
                self.s.b.request("device.insert_factory", handle._track_params(id=device["device_id"]))
                self.report.ok("native_device")
                return before
            if kind in ("vst3", "vst2", "clap"):
                state = self._state_file(device)
                if state is None and device.get("state_path"):
                    self.report.skip("plugin_state_missing", place, device.get("state_path"))
                params = {"name": device["device_name"], "format": kind, "id": str(device["device_id"])}
                if kind == "vst3":
                    params["id"] = params["id"].upper()
                if kind in ("vst3", "vst2") and device.get("role"):
                    params["role"] = device["role"]
                if state is not None:
                    params["state"] = str(state)
                result = self.s.b.request("device.insert_plugin", handle._track_params(**params), timeout=125)
                self.report.ok(kind)
                if state is not None:
                    self.report.ok("plugin_state")
                return result["device"]
            self.report.skip(f"{kind}_device", place, device.get("device_id"))
        except Exception as error:
            self.report.fail(f"{kind}_device", place, error)
        return None

    def _state_file(self, device):
        name = Path(device.get("state_path") or device.get("state_file") or "").name
        if not name or self.states is None:
            return None
        candidate = self.states / name
        return candidate if candidate.is_file() else None

    def _device_details(self, handle, index, device, where):
        place = f"{where}#{index}:{device.get('device_name')}"
        if device.get("grid"):
            self._grid(handle, index, device["grid"], place)
        if device.get("kind") == "native":
            self._native_values(handle, index, device, place)
        if device.get("display_name"):
            self._try("device_name", place, lambda: handle.rename_device(index, device["display_name"]))
        if device.get("enabled") is False:
            self._try("device_enabled", place, lambda: handle.enable_device(index, False))
        if device.get("active") is False:
            self._deactivations.append(("device_active", place, lambda: self.s.b.request(
                "device.set_active", handle._track_params(device=index, on=False))))
        if device.get("preset") or device.get("displayed_preset") not in (None, "", "init"):
            self.report.skip("preset_name", place, device.get("preset") or device.get("displayed_preset"))
        for sidechain in device.get("sidechains") or []:
            if sidechain.get("source") is not None:
                self._pending_sidechains.append((handle, index, sidechain, place))
        if device.get("kind") in ("vst3", "vst2", "clap") and device.get("sidechain") is not None:
            # A plugin's own sidechain input has no component path.
            self._pending_sidechains.append((handle, index, {"component_path": "", "source": device["sidechain"]}, place))
        for content in device.get("sample_content") or []:
            self._sample_content(handle, index, content, place)
        if device.get("model_fields"):
            self._model_fields(handle, index, device["model_fields"], place)
        for impulse in device.get("impulses") or []:
            spec = impulse.get("impulse") or {}
            if spec.get("vendor"):
                self._try("impulse", place, lambda: self.s.b.request("device.load_impulse", handle._track_params(
                    device=index, component=impulse["component"], impulse=spec)))
            elif spec:
                self.report.skip("impulse_file", place, spec)
        banks = {}
        chain_map = [self._nested(handle, index, chain, place, banks) for chain in device.get("chains") or []]
        if device.get("modulators") or device.get("kind") == "native":
            self._modulators(handle, index, device, place, chain_map)

    def _grid(self, handle, index, grid, place):
        """Replace the fresh patch with the dump's modules, then wire them.

        Modules are appended in dump order, so their positional components
        (CONTENTS/MODULES/<i>) match the dump's; module parameters follow
        with the device values.
        """
        try:
            fresh = handle.read_grid(index).get("modules") or []
            for module in reversed(fresh):
                handle.delete_grid_module(index, module["component"])
            for position, module in enumerate(grid.get("modules") or []):
                created = handle.add_grid_module(index, module["module_id"], int(module["x"]), int(module["y"]))
                if created.get("component") != module["component"]:
                    self.report.skip("grid_module_order", place, [module["component"], created.get("component")])
                    continue
                fresh_name = next((m.get("name") for m in created.get("grid", {}).get("modules", [])
                                   if m.get("component") == created["component"]), None)
                if module.get("name") and module["name"] != fresh_name:
                    self._try("grid_module_name", place, lambda: self.s.b.request("device.component_rename", handle._track_params(
                        device=index, component=module["component"], name=module["name"])))
            self.report.ok("grid_module", len(grid.get("modules") or []))
        except Exception as error:
            self.report.fail("grid_modules", place, error)
            return
        for module in grid.get("modules") or []:
            for port in module.get("inputs") or []:
                if port.get("source") and port.get("component"):
                    self._try("grid_cable", place, lambda: handle.connect_grid(index, port["component"], port["source"]))

    def _sample_content(self, handle, index, content, place):
        kind = content.get("kind")
        where = f"{place}/{content.get('component')}"
        if kind == "empty":
            return
        if kind not in ("single", "multisample"):
            self.report.skip(f"sample_{kind}", where)
            return
        params = handle._track_params(device=index, component=content["component"], kind=kind)
        try:
            if kind == "single":
                sample = dict(content["sample"])
                sample["file"] = str(self.media(sample["file"]))
                params["sample"] = sample
            else:
                zones = []
                for zone in content.get("zones") or []:
                    zone = dict(zone)
                    zone["sample"] = dict(zone["sample"], file=str(self.media(zone["sample"]["file"])))
                    zones.append(zone)
                params["zones"] = zones
        except (KeyError, TypeError) as error:
            self.report.skip("sample_missing_file", where, str(error))
            return
        self._try(f"sample_{kind}", where, lambda: self.s.b.request("device.load_sample", params, timeout=125))

    def _model_fields(self, handle, index, wanted, place):
        """Stored fields that are not parameters (a Sampler's playback mode and its
        repitch, cycles, spectral and grain settings): only those that differ from the
        fresh device are written, atom by atom in reader order."""
        try:
            fresh = self.s.b.request("device.model_fields", handle._track_params(device=index))["model_fields"]
        except Exception as error:
            self.report.fail("model_fields_read", place, error)
            return
        seen = Counter()
        for atom in wanted:
            descriptor = atom.get("descriptor")
            position = seen[descriptor]
            seen[descriptor] += 1
            current = [a for a in fresh if a.get("descriptor") == descriptor]
            if position >= len(current):
                self.report.skip("model_fields_atom", place, atom.get("component"))
                continue
            have = current[position].get("fields") or {}
            changes = {k: v for k, v in (atom.get("fields") or {}).items() if k in have and have[k] != v}
            if not changes:
                continue
            where = f"{place}/{atom.get('component')}"
            try:
                result = self.s.b.request("device.set_model_fields", handle._track_params(
                    device=index, descriptor=descriptor, index=position, fields=changes))
            except Exception as error:
                self.report.fail("model_fields", where, error)
                continue
            self.report.ok("model_fields")
            for name in result.get("skipped") or []:
                # Derived fields have no setter (a Sampler's max_zones_playing_in_parallel).
                self.report.skip("model_field_derived", where, name)

    def _native_values(self, handle, index, device, place):
        values = device.get("values") or {}
        if not values:
            return
        kinds = {p["path"]: p.get("kind") for p in device.get("parameters") or []}
        # A device loading its definition may expose only some paths at first.
        deadline = time.monotonic() + 10
        available = set()
        while True:
            try:
                available = {p["path"] for p in handle.list_parameters(index)}
            except BridgeError:
                available = set()
            if set(values) <= available or time.monotonic() > deadline:
                break
            time.sleep(.1)
        doubles = {k: float(v) * 100 for k, v in values.items()
                   if kinds.get(k) == "double" and k in available and isinstance(v, (int, float))
                   and not isinstance(v, bool) and math.isfinite(v)}
        others = {}
        for k, v in values.items():
            if k not in available:
                self.report.skip("parameter_unavailable", place, k)
            elif kinds.get(k) == "boolean" and isinstance(v, bool):
                others[k] = v
            elif kinds.get(k) == "enum" and _enum_index(v) is not None:
                others[k] = _enum_index(v)
        # Switches and modes first: they can change the range a number may take.
        if others and self._try("parameters", place, lambda: handle.parameters(index, **others)):
            self.report.ok("parameter_values", len(others) - 1)
        if doubles and self._try("parameters", place, lambda: handle.parameters(index, unit="PERCENTAGE_OF_RANGE", **doubles)):
            self.report.ok("parameter_values", len(doubles) - 1)

    def _structure(self, handle, index):
        """Modulator identities and chain components of one device (cheap read)."""
        return self.s.b.request("device.structure", handle._track_params(device=index), timeout=60)

    def _bank_chain(self, handle, index, kind, bank, component, where, banks):
        """Component path of the rebuilt chain matching one dump layer/band/pad."""
        if bank.endswith("DRUM_PADS"):
            found = re.search(r"PAD(\d+)$", component)
            if not found:
                self.report.skip("drum_pad_key", where, component)
                return None
            result = self.s.b.request("device.drum_pad", handle._track_params(
                device=index, component=bank, key=int(found.group(1))))
            self.report.ok("drum_pad")
            return result["component"]
        if bank not in banks:
            # A fresh container can already hold default chains; reuse them in order.
            existing = [c["component_path"] for c in self._structure(handle, index)["chains"]
                        if (c.get("component_path") or "").startswith(bank + "/")]
            banks[bank] = {"existing": existing, "used": 0}
        state = banks[bank]
        if state["used"] < len(state["existing"]):
            state["used"] += 1
            self.report.ok(f"{kind}_reused")
            return state["existing"][state["used"] - 1]
        if kind == "layer":
            created = handle.create_layer(index, bank)
        else:
            created = self.s.b.request("device.create_band", handle._track_params(device=index, component=bank))
        self.report.ok(f"{kind}_created")
        return created["component"]

    def _nested(self, handle, index, chain, place, banks):
        kind = chain.get("kind")
        component = chain.get("component_path")
        where = f"{place}/{component}"
        if kind == "chain":
            created = component
            sub = handle.chain(index, component)
        elif kind in ("layer", "band"):
            bank = component.rsplit("/", 1)[0]
            try:
                created = self._bank_chain(handle, index, kind, bank, component, where, banks)
            except Exception as error:
                self.report.fail(f"create_{kind}", where, error)
                return None
            if created is None:
                return None
            sub = handle.chain(index, created)
            layer = chain.get("layer") or {}
            changes = {}
            if layer.get("name"):
                changes["name"] = layer["name"]
            mixer = layer.get("mixer") or {}
            if mixer.get("volume", 1) != 1:
                changes["volume"] = mixer["volume"]
            if mixer.get("pan", 0):
                changes["pan"] = mixer["pan"]
            if mixer.get("muted"):
                changes["muted"] = True
            if changes:
                self._try("layer_settings", where, lambda: handle.update_layer(index, created, **changes))
        else:
            self.report.skip("chain_kind", where, kind)
            return None
        # Fixed and reused chains can hold the fresh container's defaults
        # (a Vocoder carrier's noise and receiver); the dump lists the exact chain.
        try:
            for extra in reversed(range(self.s.b.request("device.count", sub._track_params())["count"])):
                sub.delete_device(extra)
                self.report.ok("default_device_removed")
        except Exception as error:
            self.report.fail("default_device_removed", where, error)
        if chain.get("devices"):
            self._chain(sub, chain["devices"], where)
        return created

    @staticmethod
    def _modulator_values(modulator):
        """Staged values: doubles as PERCENTAGE_OF_RANGE (the reader's normalized x 100)."""
        values = {}
        for parameter in modulator.get("parameters") or []:
            kind = parameter.get("kind")
            if kind == "double" and parameter.get("normalized") is not None:
                values[parameter["path"]] = float(parameter["normalized"]) * 100
            elif kind == "boolean" and isinstance(parameter.get("value"), bool):
                values[parameter["path"]] = parameter["value"]
            elif kind == "enum":
                enum = parameter.get("index", _enum_index(parameter.get("value")))
                if enum is not None:
                    values[parameter["path"]] = enum
        return values

    def _modulators(self, handle, index, device, place, chain_map):
        """Rebuild a device's modulators in source order.

        A fresh device can carry default modulators (a Sampler's Vibrato).
        They are removed first, so indexes match the source exactly; the
        source's own list (with its bindings) brings back what it had.
        """
        wanted = device.get("modulators") or []
        try:
            fresh = self._structure(handle, index)["modulators"]
        except Exception as error:
            self.report.fail("modulators_read", place, error)
            return
        for extra in sorted((f["index"] for f in fresh), reverse=True):
            self._try("modulator_removed", place, lambda: self.s.b.request(
                "device.modulator_delete", handle._track_params(device=index, modulator=extra)))
        mapping = {}
        for modulator in wanted:
            where = f"{place}/mod:{modulator.get('device_name')}"
            values = self._modulator_values(modulator)
            if any(p.get("kind") == "double" and p.get("normalized") is None for p in modulator.get("parameters") or []):
                self.report.skip("modulator_native_values", where)
            params = handle._track_params(device=index, modulator_id=modulator["modulator_id"])
            if "x" in modulator:
                params.update(x=modulator["x"], y=modulator["y"])
            if values:
                params.update(values=values, unit="PERCENTAGE_OF_RANGE")
            polarity = {p["path"]: p["bipolar"] for p in modulator.get("parameters") or [] if "bipolar" in p}
            if polarity:
                params["bipolar"] = polarity
            deferred = {}
            result = None
            for _ in range(len(values) + 1):
                try:
                    result = self.s.b.request("device.modulator_add", params, timeout=125)
                    break
                except Exception as error:
                    found = re.search(r"invalid value for modulator parameter: (\S+)", str(error))
                    if not found or found.group(1) not in params.get("values", {}):
                        self.report.fail("modulator", where, error)
                        break
                    # The staged definition rejects this value; set it on the inserted modulator.
                    deferred[found.group(1)] = params["values"].pop(found.group(1))
                    if not params["values"]:
                        params.pop("values")
                        params.pop("unit", None)
            if result is None:
                continue
            time.sleep(self._pacing)  # engine pacing after insertion (see JavaDeviceMethods.add_modulator)
            mapping[modulator["index"]] = result["modulator"]
            self.report.ok("modulator")
            for name, value in deferred.items():
                double = isinstance(value, float) and not isinstance(value, bool)
                change = handle._track_params(device=index, modulator=result["modulator"], values={name: value})
                if double:
                    change["unit"] = "PERCENTAGE_OF_RANGE"
                if not self._try("modulator_value_after_insert", where, lambda: self.s.b.request(
                        "device.modulator_parameters", change)):
                    self.report.skip("modulator_value_rejected", where, name)
            if polarity:
                # Insertion clones the staged modulator without the polarity flag.
                self._try("modulator_polarity", where, lambda: self.s.b.request(
                    "device.modulator_parameters", handle._track_params(
                        device=index, modulator=result["modulator"], bipolar=polarity)))
        for modulator in wanted:
            where = f"{place}/mod:{modulator.get('device_name')}"
            target = mapping.get(modulator["index"])
            if target is None:
                continue
            name = _modulator_user_name(modulator)
            if name:
                self._try("modulator_name", where, lambda: self.s.b.request(
                    "device.modulator_rename", handle._track_params(device=index, modulator=target, name=name)))
            for parameter in modulator.get("parameters") or []:
                if parameter.get("label"):
                    # Mapping a Macro relabels it after its target; labels go in after the mappings.
                    self._pending_labels.append((handle, index, target, parameter["path"], parameter["label"], where))
            for binding in modulator.get("bindings") or []:
                self._pending_bindings.append((handle, index, target, binding, where, chain_map, mapping))

    def deactivate(self):
        for what, where, action in self._deactivations:
            self._try(what, where, action)

    def bindings(self):
        def handle_is_root(handle):
            return not getattr(handle, "_chain_steps", None)

        for handle, index, modulator, binding, where, chain_map, mapping in self._pending_bindings:
            owner = binding.get("target_owner") or {}
            parameter = binding.get("target_parameter")
            if not parameter or "device" not in owner:
                self.report.skip("binding_target", where, binding)
                continue
            params = handle._track_params(device=index, modulator=modulator, target_parameter=parameter)
            if owner.get("self"):
                pass  # the modulator's own device
            elif len(owner.get("chain_path") or []) > 1 or (owner.get("chain_path") and not handle_is_root(handle)):
                # A device deeper than the modulator owner's own chains: route from the track root.
                params.update(target_chain=[{"device": s["device"], "component": s["component"]}
                                            for s in owner["chain_path"]], target_device=owner["device"])
            elif "chain" in owner:
                component = chain_map[owner["chain"]] if owner["chain"] < len(chain_map) else None
                if component is None:
                    self.report.skip("binding_nested_target", where, binding)
                    continue
                params.update(target_component=component, target_device=owner["device"])
            elif owner["device"] != index:
                params["target_device"] = owner["device"]
            found = re.match(r"MODULATORS/(\d+)/(.*)$", parameter)
            if found and (owner.get("self") or ("chain" not in owner and owner["device"] == index)):
                rebuilt = mapping.get(int(found.group(1)))
                if rebuilt is None:
                    self.report.skip("binding_modulator_target", where, binding)
                    continue
                params["target_parameter"] = f"MODULATORS/{rebuilt}/{found.group(2)}"
            if binding.get("component"):
                params["source"] = binding["component"]
            if binding.get("amount") is not None:
                params["native_amount"] = float(binding["amount"])
            elif binding.get("amount_normalized") is not None:
                params["amount"] = float(binding["amount_normalized"])
            else:
                self.report.skip("binding_amount", where, binding)
                continue
            self._try("binding", where, lambda: self.s.b.request("device.modulator_bind", params))
        for handle, index, modulator, path, label, where in self._pending_labels:
            self._try("modulator_label", where, lambda: self.s.b.request("device.modulator_rename", handle._track_params(
                device=index, modulator=modulator, parameter=path, name=label)))

    def sidechains(self):
        for handle, index, sidechain, place in self._pending_sidechains:
            spec = self._source_spec(sidechain.get("source"))
            if spec is False or spec is None:
                self.report.skip("sidechain_source", place, sidechain)
                continue
            self._try("sidechain", place, lambda: self.s.b.request("device.set_sidechain", handle._track_params(
                device=index, component=sidechain["component_path"], source=spec)))

    # Clips -------------------------------------------------------------------

    def clips(self):
        for track, scope in self._all_tracks():
            clips = sorted(track.get("clips") or [], key=lambda c: c.get("start", 0))
            if not clips:
                continue
            handle = self._handle(track)
            where = track.get("path") or scope
            for number, clip in enumerate(clips):
                if clip.get("content") == "note":
                    self._note_clip(handle, number, clip, where)
                elif clip.get("content") == "audio":
                    self._audio_clip(handle, number, clip, where)
                else:
                    self.report.skip("clip_content", where, clip.get("content"))

    _NOTE_FIELDS = ("key", "channel", "start", "duration", "on_velocity", "off_velocity", "muted",
                    "velocity_spread", "content_start", "chance", "chance_enabled", "occurrence",
                    "occurrence_enabled", "recurrence", "recurrence_enabled", "repeat_count",
                    "repeat_curve", "repeat_enabled")

    # Launch settings of a clip Bitwig creates; only differing ones are written.
    _LAUNCH_DEFAULTS = {"launch_quantization": "DEFAULT", "launch_mode": "DEFAULT", "next_action": "STOP",
                        "next_action_time": 16, "use_loop_start_as_quantization_reference": False}

    @classmethod
    def _launch_changes(cls, clip):
        settings = clip.get("launch_settings") or {}
        return {k: v for k, v in settings.items() if k in cls._LAUNCH_DEFAULTS and v != cls._LAUNCH_DEFAULTS[k]}

    @staticmethod
    def _expressions(items):
        curves = {}
        for expression in items or []:
            name = expression.get("expression")
            points = [[p["time"], p["value"], p.get("curvature", 0), p.get("interpolation", "linear")]
                      for p in expression.get("points") or [] if (p.get("time") or 0) >= 0]
            if name and points:
                curves[name] = {"unit": "SEMITONES" if expression.get("unit") == "semitones" else "RATIO",
                                "points": points}
        return curves

    def _notes(self, notes, where):
        payload = []
        for note in notes:
            item = {k: note[k] for k in self._NOTE_FIELDS if k in note}
            expressions = self._expressions(note.get("expressions"))
            if expressions:
                item["expressions"] = expressions
            payload.append(item)
        return payload

    def _clip_settings(self, handle, number, clip, where, *, launcher=False):
        """Container settings of an arranger clip, or of the launcher clip in slot ``number``."""
        update = handle.update_launcher_clip if launcher else handle.update_clip
        # Loop region first: the play offset is validated against it. The name is always
        # written: an audio import titles the clip after its file.
        changes = {"loop": bool(clip.get("loop")),
                   "loop_start": float(clip.get("loop_start", 0)),
                   "loop_duration": float(clip.get("loop_duration") or clip["duration"]),
                   "name": clip.get("name") or ""}
        if clip.get("muted"):
            changes["muted"] = True
        rgba = _rgba(clip.get("color"))
        if rgba is not None:
            changes["color"] = clip["color"][:7]
        self._try("clip_settings", where, lambda: update(number, **changes))
        launch = self._launch_changes(clip)
        if launch:
            self._try("clip_launch_settings", where, lambda: update(number, **launch))
        if clip.get("content") == "audio":
            fades = {k: float(clip[k]) for k in ("fade_in", "fade_out") if clip.get(k)}
            if fades:
                self._try("clip_fades", where, lambda: self.s.b.request(
                    "clip.update", handle._track_params(**{"slot" if launcher else "clip": number},
                                                        changes=fades)))
        if clip.get("play_start_offset"):
            offset, duration = float(clip["play_start_offset"]), float(clip["duration"])
            if abs(offset - duration) < 1e-9:
                # Bitwig moves the play start before the end marker unless the target lies
                # beyond it; landing exactly on it is clamped one tick early. Step past it first.
                self._try("clip_offset_step", where, lambda: update(
                    number, play_start_offset=offset + duration, duration=duration))
            self._try("clip_offset", where, lambda: update(
                number, play_start_offset=offset, duration=duration))

    def _note_clip(self, handle, number, clip, where):
        try:
            self.s.b.request("clip.create_arranger_with_notes", handle._track_params(
                start=float(clip["start"]), duration=float(clip["duration"]), notes=[]))
            self.report.ok("note_clip")
        except Exception as error:
            self.report.fail("note_clip", where, error)
            return
        notes = self._notes(clip.get("notes") or [], where)
        if notes:
            for start in range(0, len(notes), 4096):
                if start:
                    self.report.skip("note_clip_over_4096", where, len(notes))
                    break
                if self._try("notes", where, lambda: handle.replace_notes(number, notes[:4096])):
                    self.report.ok("note", len(notes) - 1)
        self._clip_settings(handle, number, clip, where)

    def _audio_clip(self, handle, number, clip, where):
        events = clip.get("audio_events") or []
        if not events:
            self.report.skip("empty_audio_clip", where)
            return
        first = events[0]
        source = self.media(first["file"])
        try:
            handle.import_audio(source, start=float(clip["start"]), duration=float(clip["duration"]),
                                source_offset=0)
            self.report.ok("audio_clip")
        except Exception as error:
            self.report.fail("audio_clip", where, error)
            return
        self._audio_event(handle, number, 0, first, where, replace=True)
        for position, event in enumerate(events[1:], start=1):
            try:
                handle.import_audio_event(number, self.media(event["file"]), start=float(event["start"]),
                                          duration=float(event["duration"]))
                self.report.ok("audio_event")
            except Exception as error:
                self.report.fail("audio_event", where, error)
                continue
            self._audio_event(handle, number, position, event, where, replace=False)
        self._clip_settings(handle, number, clip, where)

    def _audio_event(self, handle, number, index, event, where, *, replace):
        changes = {"start": float(event["start"]), "duration": float(event["duration"])}
        warp = event.get("warp_mode")
        if warp and warp != "RAW":
            markers = [[m["time"], m["content_time"]] for m in event.get("warp_markers") or []]
            if event.get("reverse"):
                # Reversed events store mirrored source times; write the forward
                # mapping and let the native reverse mirror it again.
                length = float(event.get("sample_duration_seconds") or 0)
                markers = sorted([[t, length - c] for t, c in markers])
            if len(markers) >= 2 and all(b[0] > a[0] and b[1] > a[1] for a, b in zip(markers, markers[1:])):
                changes["warp_mode"] = warp
                changes["warp_markers"] = markers
            else:
                self.report.skip("warp_markers", where, {"mode": warp, "reverse": event.get("reverse")})
        if warp and warp != "RAW" and event.get("content_start"):
            changes["content_offset"] = float(event["content_start"])
        elif warp == "RAW" and event.get("sample_play_start_seconds") and not event.get("reverse"):
            changes["source_offset"] = float(event["sample_play_start_seconds"])
        for key in ("fade_in", "fade_out"):
            if event.get(key):
                changes[key] = event[key]
        if event.get("muted"):
            changes["muted"] = True
        for key in ("preserve_formants", "preview_onsets"):
            if key in event:
                changes[key] = bool(event[key])
        for key in ("preserve_transients_mode", "transient_rate", "warp_grain_size", "warp_tail_mode",
                    "formant_envelope_resolution"):
            if event.get(key):
                changes[key] = event[key]
        self._try("audio_event_settings", where, lambda: self.s.b.request(
            "audio_event.update", handle._track_params(clip=number, event=index, changes=changes), timeout=125))
        if event.get("reverse"):
            self._try("audio_reverse", where, lambda: handle.update_audio_event(number, index, reverse=True))
        curves = self._expressions(event.get("expressions"))
        # Import leaves a gain curve the source may not have; clear it unless replaced.
        curves.setdefault("gain", {"unit": "RATIO", "points": []})
        self._try("audio_expressions", where, lambda: self.s.b.request(
            "audio_event.update", handle._track_params(clip=number, event=index, changes={"expressions": curves}),
            timeout=125))

    def launcher(self):
        for track, scope in self._all_tracks():
            handle = self._handle(track)
            where = track.get("path") or scope
            for slot in track.get("clip_slots") or []:
                clip = (slot or {}).get("clip")
                if not clip:
                    if slot and slot.get("has_stop") is False:
                        self._try("launcher_stop_button", where, lambda: self.s.b.request(
                            "launcher.clear", handle._track_params(slot=slot["index"], has_stop=False)))
                    continue
                if clip.get("content") == "note":
                    if self._try("launcher_clip", where, lambda: handle.put_launcher_notes(
                            slot["index"], self._notes(clip.get("notes") or [], where), clip["duration"],
                            name=clip.get("name") or "", loop=bool(clip.get("loop", True)),
                            has_stop=slot.get("has_stop"))):
                        # The new clip loops its full length; the source loop can differ.
                        self._clip_settings(handle, slot["index"], clip, where, launcher=True)
                else:
                    self.report.skip("launcher_audio", where)

    # Automation ----------------------------------------------------------------

    def automation(self):
        for track, scope in self._all_tracks():
            handle = self._handle(track)
            where = track.get("path") or scope
            for lane in track.get("automation") or []:
                self._lane(handle, lane, where)

    @staticmethod
    def _rows(kind, points):
        """Writer rows: raw values, with the stored curve shape when the reader has it."""
        rows = []
        for p in points:
            if kind == "real":
                rows.append([p["time"], p.get("raw_value", p["value"]), p.get("curvature", 0),
                             p.get("model_interpolation", p.get("interpolation", "linear"))])
            elif kind == "enum":
                rows.append([p["time"], p.get("raw_value", p["value"])])
            else:
                rows.append([p["time"], p["value"]])
        return rows

    def _lane(self, handle, lane, where):
        # Bitwig stores constant lanes with an anchor at a large negative time.
        # Lanes with automation clips list the lane's own points apart from the
        # clips' (the composite "points" interleave both).
        clips = lane.get("clips") or []
        source = lane["base_points"] if clips and "base_points" in lane else lane.get("points") or []
        points, last = [], -1.0
        for point in source:
            time_ = point.get("time") or 0
            if time_ < 0 or time_ < last:
                self.report.skip("automation_point_out_of_order", where, time_)
                continue
            points.append(point)
            last = time_
        kinds = [p.get("kind") for p in points] + [p.get("kind") for c in clips for p in c.get("points") or []]
        if not kinds:
            return
        target = lane.get("target_path")
        kind = kinds[0]
        if not target or lane.get("target_status") != "model":
            # The stored lane has no target (left behind by a deleted parameter).
            self.report.skip("automation_orphan_lane", where, len(points))
            return
        if target.startswith("MIXER/SEND_"):
            # Sends are addressed by effect track UUIDs the dump does not map
            # (the source's own were left pointing at deleted effect tracks).
            self.report.skip("automation_send_lane", where, target)
            return
        if target.startswith("TRACK_CONTROLS_DEVICE:"):
            self.report.skip("automation_track_controls", where, target)
            return
        if kind not in ("real", "boolean", "enum", "integer"):
            self.report.skip(f"automation_{kind}", where, target)
            return
        if len(points) < 2:
            if not clips:
                self.report.skip("automation_single_point", where, target)
                return
            # The lane needs its own points to exist; hold the first clip value around the clips.
            first = (points or clips[0]["points"])[0]
            end = max(c["end"] for c in clips)
            points = [dict(first, time=0.0), dict(first, time=float(end))]
            self.report.skip("automation_base_synthesized", where, target)
        params = handle._track_params(target=target, kind=kind, points=self._rows(kind, points))
        if "device" in lane:
            params["device"] = lane["device"]
        if not self._try(f"automation_{lane.get('target_scope')}", where,
                         lambda: self.s.b.request("automation.write_target", params, timeout=125)):
            return
        if clips:
            payload = [{"start": c["start"], "end": c["end"], "loop": bool(c.get("loop")),
                        "loop_start": c.get("loop_start", 0), "loop_duration": c.get("loop_duration", c["end"] - c["start"]),
                        "play_start_offset": c.get("play_start_offset", 0),
                        "points": self._rows(kind, c.get("points") or [])} for c in clips]
            request = dict(params, clips=payload)
            request.pop("points")
            if self._try("automation_clips", where, lambda: self.s.b.request("automation.write_clips", request, timeout=125)):
                self.report.ok("automation_clip", len(clips))

    # Scenes and markers ----------------------------------------------------------

    def scenes(self):
        existing = len(self.s.scenes())
        for scene in self.dump.get("scenes") or []:
            index = scene.get("index", existing)
            while existing <= index:
                self.s.insert_scene()
                existing += 1
            changes = {}
            if scene.get("name"):
                changes["name"] = scene["name"]
            if scene.get("comment"):
                changes["comment"] = scene["comment"]
            if _rgba(scene.get("color")) is not None:
                changes["color"] = scene["color"][:7]
            if changes:
                self._try("scene", f"scene {index}", lambda: self.s.update_scene(index, **changes))

    def markers(self):
        for marker in self.dump.get("cue_markers") or []:
            color = marker.get("color")
            self._try("cue_marker", marker.get("name", ""), lambda: self.s.add_cue_marker(
                float(marker.get("time", marker.get("beat", 0))), marker.get("name") or " ",
                color[:7] if _rgba(color) is not None else None))

    # Titles ----------------------------------------------------------------------

    def restore_titles(self):
        ordered = sorted(((track, scope) for track, scope in self._all_tracks() if scope != "master"),
                         key=lambda item: -self.paths[id(item[0])].count("/"))
        for track, scope in ordered:
            handle = self._handle(track)
            title = self.titles[id(track)]
            self._try("title", track.get("path") or scope, lambda: self.s.b.request(
                "track.rename", handle._target_params(name=title)))


def reconstruct(song: JavaSong, dump: dict, **options) -> Report:
    """Rebuild ``dump`` into the selected project; see :class:`Reconstructor`."""
    return Reconstructor(song, dump, **options).run()
