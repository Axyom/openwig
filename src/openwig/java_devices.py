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
        import time
        deadline = time.monotonic() + timeout
        while job["state"] == "running":
            if time.monotonic() >= deadline:
                raise TimeoutError(f"plugin state operation timed out: {job['job']}")
            time.sleep(.05)
            job = self.s.b.request("device.plugin_state_status", self._track_params(job=job["job"]))
        if job["state"] != "complete":
            raise BridgeError(job.get("error", "plugin state operation failed"))
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
