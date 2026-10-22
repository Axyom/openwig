"""Device authoring shared by top-level and nested Java bridge chains."""
from __future__ import annotations

from typing import Self

from openwig.bridge import BridgeError


class JavaDeviceMethods:
    """Device operations; concrete handles provide transport and addressing."""

    def read_device(self, index: int) -> dict:
        """Read identity, nested chains, and resident native/plugin parameters."""
        params = self._track_params(device=self._clip_index(index))
        params.pop("track", None)
        params["path"] = self._read_path()
        return self.s.b.request("device.read", params, timeout=125)

    def add_device(self, name: str) -> Self:
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

    def insert_plugin(self, name: str, plugin_id: str, *, state=None) -> dict:
        """Append a VST3 by its 32-digit class ID, optionally restoring a .vstpreset.

        Returns the device index and initial snapshot. Engine loading is
        asynchronous; resident parameters can become available shortly afterward.
        """
        import re
        from pathlib import Path
        if not isinstance(name, str) or not name.strip():
            raise ValueError("plugin name required")
        if not isinstance(plugin_id, str) or not re.fullmatch(r"[0-9a-fA-F]{32}", plugin_id):
            raise ValueError("VST3 class ID requires 32 hex digits")
        params = dict(name=name, format="vst3", id=plugin_id.upper())
        if state is not None:
            source = Path(state).resolve()
            if not source.is_file():
                raise ValueError("plugin state file required")
            with source.open("rb") as f:
                header = f.read(48)
            if len(header) < 48 or header[:4] != b"VST3" or header[8:40].upper() != plugin_id.upper().encode("ascii"):
                raise ValueError("state is not a VST3 preset for this class ID")
            params["state"] = str(source)
        return self.s.b.request("device.insert_plugin", self._track_params(**params), timeout=125)

    def plugin_parameters(self, index: int) -> dict:
        """Read resident plugin parameter IDs/normalized values; report unavailable caches."""
        return self.s.b.request("device.plugin_parameters", self._track_params(device=self._clip_index(index)))

    def _wait_plugin_state(self, job: dict, timeout: float = 40) -> dict:
        return self._wait_device_operation(job, timeout, status_method="device.plugin_state_status")

    def _wait_device_operation(self, job: dict, timeout: float = 40,
                               *, status_method="device.operation_status") -> dict:
        import time
        deadline = time.monotonic() + timeout
        while job["state"] == "running":
            if time.monotonic() >= deadline:
                raise TimeoutError(f"device operation timed out: {job['job']}")
            time.sleep(.05)
            job = self.s.b.request(status_method, self._track_params(job=job["job"]))
        if job["state"] != "complete":
            raise BridgeError(job.get("error", "device operation failed"))
        return job

    def capture_plugin_state(self, index: int) -> dict:
        """Capture the engine's plugin state into the document for native save/export."""
        import time
        deadline = time.monotonic() + 40
        while True:
            job = self.s.b.request("device.capture_plugin_state", self._track_params(device=self._clip_index(index)))
            if job["state"] == "busy":
                if time.monotonic() >= deadline:
                    raise TimeoutError("plugin was still capturing its previous state")
                time.sleep(.05)
                continue
            result = self._wait_plugin_state(job, timeout=max(.1, deadline - time.monotonic()))
            if not result.get("dirty", False):
                return result
            if time.monotonic() >= deadline:
                raise TimeoutError("plugin kept changing while its state was captured")

    def set_plugin_parameters(self, index: int, values: dict) -> dict:
        """Set resident parameter IDs to normalized 0..1, then capture native state.

        Values are validated together before writing. The SDK waits 100 ms for
        queued engine changes, then captures state for save_project/reopen.
        This measured pacing is verified with kHs Haas; it is not a general
        engine acknowledgement. Audio must be active.
        """
        if not isinstance(values, dict) or not values:
            raise ValueError("nonempty plugin parameter values required")
        payload = {}
        for key, value in values.items():
            if not ((type(key) is int and -2147483648 <= key <= 2147483647)
                    or (isinstance(key, str) and key.lstrip("-").isdigit())):
                raise ValueError("parameter IDs must be signed 32-bit integers")
            if not -2147483648 <= int(key) <= 2147483647 or str(int(key)) in payload:
                raise ValueError("parameter ID outside signed 32-bit range or duplicated")
            number = self._clip_number(value, "plugin parameter")
            if not 0 <= number <= 1:
                raise ValueError("plugin parameter values must be in 0..1")
            payload[str(int(key))] = number
        result = self.s.b.request("device.plugin_parameters", self._track_params(
            device=self._clip_index(index), values=payload))
        # The native parameter path queues engine updates. A state-capture
        # callback alone does not fence that queue (live regression gate).
        # This is measured pacing, not a general engine acknowledgement.
        import time
        time.sleep(.1)
        self.capture_plugin_state(index)
        return result

    def export_plugin_state(self, index: int, file) -> dict:
        """Export current VST3 engine state to a fresh .vstpreset file."""
        from pathlib import Path
        target = Path(file).resolve()
        if target.suffix.lower() != ".vstpreset" or target.exists():
            raise ValueError("fresh .vstpreset destination required")
        job = self.s.b.request("device.export_plugin_state", self._track_params(
            device=self._clip_index(index), file=str(target)))
        return self._wait_plugin_state(job)

    def enable_device(self, index: int, on: bool = True) -> Self:
        self.s.b.request("device.set_enabled", self._track_params(index=int(index), on=bool(on)))
        return self

    def rename_device(self, index: int, name: str) -> Self:
        self.s.b.request("device.rename", self._track_params(device=int(index), name=name))
        return self

    def expand_device(self, index: int, on: bool = True) -> Self:
        self.s.b.request("device.set_expanded", self._track_params(device=int(index), on=bool(on)))
        return self

    def delete_device(self, index: int) -> Self:
        before = self.s.b.request("device.count", self._track_params())["count"]
        after = self.s.b.request("device.delete", self._track_params(device=int(index)))["count"]
        if after != before - 1:
            raise BridgeError("device count did not decrease after deletion")
        return self

    def parameters(self, device_index: int = 0, unit: str | None = None,
                   **values) -> Self:
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

    def chain(self, device: int, component: str) -> JavaDeviceChain:
        """Address an existing nested chain by its reader component_path."""
        return JavaDeviceChain(self, device, component)

    def read_grid(self, device: int = 0) -> dict:
        """Read module UUIDs, component addresses, positions, ports and cables."""
        return self.s.b.request("device.grid_read", self._track_params(
            device=self._clip_index(device)), timeout=125)

    @staticmethod
    def _grid_position(x, y):
        if any(type(v) is not int or abs(v) > 1000000 for v in (x, y)):
            raise ValueError("Grid coordinates must be integers within +/-1000000")

    def add_grid_module(self, device: int, module_id: str, x: int, y: int) -> dict:
        """Insert a factory module by UUID; rejects occupied grid cells."""
        from uuid import UUID
        try:
            module_id = str(UUID(module_id))
        except (ValueError, TypeError, AttributeError) as error:
            raise ValueError("Grid module UUID required") from error
        self._grid_position(x, y)
        return self.s.b.request("device.grid_add", self._track_params(
            device=self._clip_index(device), module_id=module_id, x=x, y=y), timeout=125)

    def move_grid_module(self, device: int, component: str, x: int, y: int) -> dict:
        """Move one module to unoccupied grid cells."""
        JavaDeviceChain._component(component)
        self._grid_position(x, y)
        return self.s.b.request("device.grid_move", self._track_params(
            device=self._clip_index(device), component=component, x=x, y=y))

    def delete_grid_module(self, device: int, component: str) -> dict:
        """Delete one module with native cable cleanup; refresh positional addresses."""
        JavaDeviceChain._component(component)
        return self.s.b.request("device.grid_delete", self._track_params(
            device=self._clip_index(device), component=component))

    def connect_grid(self, device: int, input: str, output: str | None) -> dict:
        """Connect a module output to an input; None disconnects that input."""
        JavaDeviceChain._component(input)
        if output is not None:
            JavaDeviceChain._component(output)
        return self.s.b.request("device.grid_connect", self._track_params(
            device=self._clip_index(device), input=input, output=output))

    def chain_banks(self, device: int) -> list[dict]:
        """Discover parallel chain containers and their layer-creation support."""
        return self.s.b.request("device.chain_banks", self._track_params(
            device=self._clip_index(device)))["banks"]

    def create_layer(self, device: int, component: str) -> dict:
        """Append a parallel layer; returns its native component address."""
        JavaDeviceChain._component(component)
        return self.s.b.request("device.create_layer", self._track_params(
            device=self._clip_index(device), component=component), timeout=125)

    def read_layer(self, device: int, component: str) -> dict:
        """Read a nested chain's stored/displayed name and optional mixer."""
        JavaDeviceChain._component(component)
        return self.s.b.request("device.layer_read", self._track_params(
            device=self._clip_index(device), component=component))

    def update_layer(self, device: int, component: str, **changes) -> dict:
        """Edit name, volume (amplitude), volume_db, pan (-1..1) or muted."""
        JavaDeviceChain._component(component)
        if not changes or set(changes) - {"name", "volume", "volume_db", "pan", "muted"}:
            raise ValueError("supported layer changes required")
        if "volume" in changes and "volume_db" in changes:
            raise ValueError("choose volume or volume_db")
        if "name" in changes and (not isinstance(changes["name"], str) or len(changes["name"]) > 256):
            raise ValueError("layer name string required (maximum 256)")
        if "muted" in changes and type(changes["muted"]) is not bool:
            raise ValueError("muted must be boolean")
        for key, low, high in [("volume", 0, 10 ** (6 / 20)), ("volume_db", -120, 6), ("pan", -1, 1)]:
            if key in changes:
                value = self._clip_number(changes[key], key)
                if not low <= value <= high:
                    raise ValueError(f"{key} out of range")
        return self.s.b.request("device.layer_update", self._track_params(
            device=self._clip_index(device), component=component, changes=changes))

    def delete_layer(self, device: int, component: str) -> dict:
        """Remove one parallel layer and its native ownership/routing references."""
        JavaDeviceChain._component(component)
        job = self.s.b.request("device.layer_delete", self._track_params(
            device=self._clip_index(device), component=component), timeout=125)
        return self._wait_device_operation(job)["data"]

    def move_layer(self, device: int, component: str, index: int) -> dict:
        """Move a parallel layer to its final index; returns its new address."""
        JavaDeviceChain._component(component)
        job = self.s.b.request("device.layer_move", self._track_params(
            device=self._clip_index(device), component=component, index=self._clip_index(index)), timeout=125)
        return self._wait_device_operation(job)["data"]

    # Modulators ---------------------------------------------------------------

    @staticmethod
    def _modulator_target(parameter):
        if not isinstance(parameter, str) or not parameter.strip() or parameter.startswith("/") \
                or ".." in parameter or len(parameter) > 1024:
            raise ValueError("target parameter path required")
        return parameter

    def modulators(self, device: int) -> list[dict]:
        """Read the device's modulators: identity, grid cell, parameters, bindings."""
        return self.read_device(device)["modulators"]

    def add_modulator(self, device: int, modulator: str, *, x: int | None = None,
                      y: int | None = None, values: dict | None = None) -> dict:
        """Insert a factory modulator by catalog name or UUID ("Add modulator").

        Without a cell Bitwig takes the first free one; an explicit (x, y)
        cell (y in 0..2) must be free. ``values`` (as for
        ``set_modulator_parameters``, normalized) are applied before insertion
        so the engine starts with them. Returns the new modulator's index.

        The engine instantiates a new modulator asynchronously: a parameter
        write sent immediately after insertion was live-verified to be lost by
        the engine while the document kept it. This call therefore waits a
        measured 250 ms before returning (0 ms failed 3/3; 50 to 500 ms passed).
        That is pacing, not an engine acknowledgement; prefer ``values``.
        """
        from uuid import UUID
        if not isinstance(modulator, str) or not modulator.strip():
            raise ValueError("modulator name or UUID required")
        if (x is None) != (y is None):
            raise ValueError("give both x and y, or neither")
        cell = {}
        if x is not None:
            if type(x) is not int or not 0 <= x <= 4095 or type(y) is not int or not 0 <= y <= 2:
                raise ValueError("modulator cell needs integer x 0..4095 and y 0..2")
            cell = dict(x=x, y=y)
        try:
            modulator_id = str(UUID(modulator))
        except ValueError:
            matches = [m["modulator_id"] for m in self.s.modulator_catalog() if m["name"] == modulator]
            if len(matches) != 1:
                raise ValueError(f"unknown factory modulator: {modulator}") from None
            modulator_id = matches[0]
        if values is not None:
            self._modulator_values(values)
            cell["values"] = values
        result = self.s.b.request("device.modulator_add", self._track_params(
            device=self._clip_index(device), modulator_id=modulator_id, **cell), timeout=125)
        import time
        time.sleep(.25)
        return result

    def modulator_parameters(self, device: int, modulator: int, unit: str | None = None) -> dict:
        """Read modulator parameters: normalized 0..1 numbers (or ``unit``), booleans, enum indexes."""
        return self.s.b.request("device.modulator_parameters", self._track_params(
            device=self._clip_index(device), modulator=self._clip_index(modulator), unit=unit))["values"]

    def set_modulator_parameters(self, device: int, modulator: int, values: dict,
                                 unit: str | None = None) -> dict:
        """Set modulator parameters in one undo step; the batch is validated first."""
        self._modulator_values(values)
        return self.s.b.request("device.modulator_parameters", self._track_params(
            device=self._clip_index(device), modulator=self._clip_index(modulator),
            values=values, unit=unit))["values"]

    def _modulator_values(self, values):
        if not isinstance(values, dict) or not values:
            raise ValueError("nonempty modulator parameter values required")
        for key, value in values.items():
            if not isinstance(key, str) or not key:
                raise ValueError("modulator parameter paths must be strings")
            if not isinstance(value, (bool, int, float)):
                raise ValueError(f"invalid value for {key}")
            if isinstance(value, float):
                self._clip_number(value, key)

    def bind_modulator(self, device: int, modulator: int, target_parameter: str, amount: float, *,
                       source: str | None = None, target_device: int | None = None) -> dict:
        """Create or update a modulation mapping; ``amount`` is -1..1 of the target's range.

        ``target_parameter`` is a native parameter path (as listed by
        ``list_parameters``) on this device or on ``target_device`` in the same
        chain; Bitwig's own target rules apply. ``source`` picks a modulation
        output when a modulator has several.
        """
        amount = self._clip_number(amount, "amount")
        if not -1 <= amount <= 1:
            raise ValueError("amount must be within -1..1")
        return self.s.b.request("device.modulator_bind", self._modulation_params(
            device, modulator, target_parameter, source, target_device, amount=amount))

    def unbind_modulator(self, device: int, modulator: int, target_parameter: str, *,
                         source: str | None = None, target_device: int | None = None) -> dict:
        """Remove one existing modulation mapping (native "Clear modulation")."""
        return self.s.b.request("device.modulator_unbind", self._modulation_params(
            device, modulator, target_parameter, source, target_device))

    def _modulation_params(self, device, modulator, parameter, source, target_device, **extra):
        params = self._track_params(device=self._clip_index(device), modulator=self._clip_index(modulator),
                                    target_parameter=self._modulator_target(parameter), **extra)
        if source is not None:
            if not isinstance(source, str) or not source:
                raise ValueError("modulation source key must be a string")
            params["source"] = source
        if target_device is not None:
            params["target_device"] = self._clip_index(target_device)
        return params

    def move_modulator(self, device: int, modulator: int, x: int, y: int) -> dict:
        """Move a modulator to a free grid cell (native "Move Modulators")."""
        if type(x) is not int or not 0 <= x <= 4095 or type(y) is not int or not 0 <= y <= 2:
            raise ValueError("modulator cell needs integer x 0..4095 and y 0..2")
        return self.s.b.request("device.modulator_move", self._track_params(
            device=self._clip_index(device), modulator=self._clip_index(modulator), x=x, y=y))

    def delete_modulator(self, device: int, modulator: int) -> dict:
        """Delete a modulator and its mappings; later modulator indexes shift down."""
        return self.s.b.request("device.modulator_delete", self._track_params(
            device=self._clip_index(device), modulator=self._clip_index(modulator)))


class JavaDeviceChain(JavaDeviceMethods):
    """A nested device chain; exposes device operations without track/clip edits."""

    def __init__(self, parent, device: int, component: str):
        self._component(component)
        self._clip_index(device)
        self.s = parent.s
        self._root_track = getattr(parent, "_root_track", parent)
        self._chain_steps = [*getattr(parent, "_chain_steps", []),
                             {"device": device, "component": component}]
        if len(self._chain_steps) > 32:
            raise ValueError("nested chain depth exceeds 32")

    @staticmethod
    def _component(value):
        if not isinstance(value, str) or not value.strip() or ".." in value or len(value) > 2048:
            raise ValueError("native component path required")

    @staticmethod
    def _clip_index(value):
        if type(value) is not int or value < 0:
            raise ValueError("device index must be a nonnegative integer")
        return value

    @staticmethod
    def _clip_number(value, key):
        import math
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError(f"{key} must be a finite number")
        return float(value)

    def _read_path(self):
        return self._root_track._read_path()

    def _track_params(self, **extra):
        import copy
        return {"path": self._read_path(), "chain": copy.deepcopy(self._chain_steps), **extra}
