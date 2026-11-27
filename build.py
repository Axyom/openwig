"""Build both openwig mods.

    python build.py [--check] [--release]

Builds mods/openwig, then mods/openwig-agent (which compiles against the first).
--check runs Patchbay's offline check on a folder holding both mods and the
frameworks they need. --release writes scratch/release/openwig-<version>.zip and
scratch/release/openwig-agent-<version>.zip, one ZIP per mod for the GitHub release.
Both mods must carry the same version.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

REPO = Path(__file__).resolve().parent
MODS = ["openwig", "openwig-agent"]


def main() -> None:
    versions = {json.loads((REPO / "mods" / m / "mod.json").read_text("utf-8"))["mod"]["version"] for m in MODS}
    if len(versions) != 1:
        raise SystemExit(f"mods disagree on the version: {sorted(versions)}")
    spec = importlib.util.spec_from_file_location("build_openwig_agent", REPO / "mods" / "openwig-agent" / "build.py")
    agent = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(agent)
    bridge = agent.bridge  # the agent's build loads the bridge's: reuse it

    def release() -> None:
        bridge.release()
        agent.release()

    bridge.cli(__doc__, agent.build_both, agent.check, release)


if __name__ == "__main__":
    main()
