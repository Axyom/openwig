"""Cross-platform controller installer.

Bitwig Studio reads user-controller scripts from a well-known directory per
platform. We bundle `openwig_bridge.control.js` as package data and copy it there
on `openwig install`.

Paths (per Bitwig docs):
    Windows : %USERPROFILE%\\Documents\\Bitwig Studio\\Controller Scripts
    macOS   : ~/Documents/Bitwig Studio/Controller Scripts
    Linux   : ~/Bitwig Studio/Controller Scripts
"""
from __future__ import annotations

import shutil
import sys
from importlib.resources import as_file, files
from pathlib import Path

from openwig.paths import data_dir as _data_dir

CONTROLLER_FILENAME = "openwig_bridge.control.js"
DEFAULTS_FILENAME = "symbols_default.json"  # bootstrap obfuscated-symbol mapping (DATA)


def _bitwig_user_scripts_dir() -> Path:
    """Return the per-platform Bitwig user-controller-scripts directory."""
    home = Path.home()
    if sys.platform == "win32":
        return home / "Documents" / "Bitwig Studio" / "Controller Scripts"
    if sys.platform == "darwin":
        return home / "Documents" / "Bitwig Studio" / "Controller Scripts"
    return home / "Bitwig Studio" / "Controller Scripts"


def _bundled_controller() -> Path:
    """Locate the bundled controller .js inside the installed package."""
    res = files("openwig.controller").joinpath(CONTROLLER_FILENAME)
    with as_file(res) as p:
        return Path(p)


def install_controller(*, force: bool = False, dry_run: bool = False) -> int:
    src = _bundled_controller()
    dst_dir = _bitwig_user_scripts_dir()
    dst = dst_dir / CONTROLLER_FILENAME

    if not dst_dir.exists():
        print(f"[openwig] controller dir not found: {dst_dir}", file=sys.stderr)
        print("              -> launch Bitwig Studio at least once, then re-run.", file=sys.stderr)
        return 2

    if dst.exists() and not force:
        print(f"[openwig] {dst} already exists - pass --force to overwrite.")
        return 1

    if dry_run:
        verb = "overwrite" if dst.exists() else "install"
        print(f"[openwig] would {verb} -> {dst}")
        return 0

    existed = dst.exists()
    shutil.copyfile(src, dst)
    print(f"[openwig] installed -> {dst}")

    # Copy the bootstrap symbol-mapping DATA file to the openwig data dir, where the controller
    # reads it at init. The obfuscated names live here as data, not in the controller code.
    try:
        data_dst = _data_dir()
        data_dst.mkdir(parents=True, exist_ok=True)
        res = files("openwig.controller").joinpath(DEFAULTS_FILENAME)
        with as_file(res) as p:
            shutil.copyfile(Path(p), data_dst / DEFAULTS_FILENAME)
        print(f"[openwig] symbol defaults -> {data_dst / DEFAULTS_FILENAME}")
    except Exception as exc:  # noqa: BLE001
        # Not a warning: without the data file the controller has NO symbol names at all
        # (every name lives in symbols_default.json, none in code).
        print(f"[openwig] ERROR: could not install symbol defaults: {exc}", file=sys.stderr)
        print(f"              -> the bridge is unusable without {DEFAULTS_FILENAME}; "
              f"fix access to {_data_dir()} and re-run.", file=sys.stderr)
        return 2

    if existed:
        # Bitwig watches this file and auto-reloads the script a few seconds after it
        # changes - new handlers go live with no manual step. (A reload can leave value
        # setters / observers flaky; if controls act stale, remove + re-add the
        # controller once in Settings -> Controllers to fully reset it.)
        print("[openwig] Bitwig will auto-reload the controller in a few seconds.")
    else:
        print("[openwig] next: Bitwig Studio -> Settings -> Controllers -> openwig -> Add -> OpenwigBridge")
    return 0


def uninstall_controller(*, dry_run: bool = False) -> int:
    dst = _bitwig_user_scripts_dir() / CONTROLLER_FILENAME
    if not dst.exists():
        print(f"[openwig] nothing to remove (not installed at {dst}).")
        return 0
    if dry_run:
        print(f"[openwig] would remove -> {dst}")
        return 0
    dst.unlink()
    print(f"[openwig] removed -> {dst}")
    return 0
