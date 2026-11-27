"""Build both openwig mods.

    python build.py [--check] [--release]

Builds mods/openwig, then mods/openwig-agent (which compiles against the first).
--check runs Patchbay's offline check on a folder holding both mods and the
frameworks they need. --release writes scratch/release/openwig-<version>.zip and
scratch/release/openwig-agent-<version>.zip, one ZIP per mod for the GitHub release.
Both mods must carry the same version.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

REPO = Path(__file__).resolve().parent
MODS = ["openwig", "openwig-agent"]


def load(mod: str):
    spec = importlib.util.spec_from_file_location(f"build_{mod.replace('-', '_')}", REPO / "mods" / mod / "build.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="run Patchbay's offline check on both mods")
    parser.add_argument("--release", action="store_true", help="write one release ZIP per mod to scratch/release")
    args = parser.parse_args()
    versions = {json.loads((REPO / "mods" / m / "mod.json").read_text("utf-8"))["mod"]["version"] for m in MODS}
    if len(versions) != 1:
        raise SystemExit(f"mods disagree on the version: {sorted(versions)}")
    bridge, agent = (load(m) for m in MODS)
    bridge.build()
    agent.build()
    if args.check:
        agent.check()
    if args.release:
        bridge.release()
        agent.release()


if __name__ == "__main__":
    main()
