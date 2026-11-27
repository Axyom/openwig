"""Build the OpenWig Agent Patchbay mod (Patchbay docs/sdk/building.md, steps 1-8).

    python mods/openwig-agent/build.py [--check] [--release]

Writes the installable folder to scratch/patchbay-agent-out/openwig-agent. --check
then runs Patchbay's offline check on a folder holding this mod, the OpenWig
Bridge and the frameworks they need. Run alone, it builds the bridge first: this
mod compiles against the bridge's helper.jar (it adds its mod tools to the
bridge's MCP server). --release also writes scratch/release/openwig-agent-<version>.zip.
"""
from __future__ import annotations

import importlib.util
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]

_spec = importlib.util.spec_from_file_location("bridge_build", REPO / "mods" / "openwig" / "build.py")
bridge = importlib.util.module_from_spec(_spec)  # the bridge build: paths and the finished-manifest rules
_spec.loader.exec_module(bridge)

WORK = REPO / "scratch" / "patchbay-agent-build"
CHECK = REPO / "scratch" / "patchbay-agent-out"
OUT = CHECK / "openwig-agent"
FRAMEWORKS = ["panel-management-framework", "action-menu-composition-framework", "preferences-composition-framework"]


def build() -> None:
    """This mod alone; the bridge's helper.jar must be built (bridge.build())."""
    deps = tuple(bridge.BML / "mods" / f / "helper.jar" for f in FRAMEWORKS) + (bridge.OUT / "helper.jar",)
    bridge.build_mod(HERE, WORK, OUT, deps, package=True)


def check() -> None:
    for name in FRAMEWORKS:
        shutil.rmtree(CHECK / name, ignore_errors=True)
        shutil.copytree(bridge.BML / "mods" / name, CHECK / name)
    shutil.rmtree(CHECK / "openwig", ignore_errors=True)
    shutil.copytree(bridge.OUT, CHECK / "openwig")
    bridge.offline_check(CHECK)


def release() -> Path:
    """The release ZIP, by the bridge build's rules: the installed folder plus src/ and build.json."""
    return bridge.release(HERE, OUT)


def build_both() -> None:
    bridge.build()
    build()


if __name__ == "__main__":
    bridge.cli(__doc__, build_both, check, release)
