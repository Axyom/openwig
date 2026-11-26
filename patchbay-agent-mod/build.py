"""Build the OpenWig Agent Patchbay mod (Patchbay docs/sdk/building.md, steps 1-8).

    python patchbay-agent-mod/build.py [--check]

Writes the installable folder to scratch/patchbay-agent-out/openwig-agent. --check
then runs Patchbay's offline check on a folder holding this mod, the OpenWig
Bridge and the frameworks they need (the bridge must be built first).
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("bridge_build", REPO / "patchbay-mod" / "build.py")
bridge = importlib.util.module_from_spec(_spec)  # the bridge build: paths and the finished-manifest rules
_spec.loader.exec_module(bridge)

WORK = REPO / "scratch" / "patchbay-agent-build"
CHECK = REPO / "scratch" / "patchbay-agent-out"
OUT = CHECK / "openwig-agent"
FRAMEWORKS = ["panel-management-framework", "action-menu-composition-framework", "preferences-composition-framework"]


def build() -> None:
    shutil.rmtree(WORK, ignore_errors=True)
    shutil.rmtree(OUT, ignore_errors=True)
    classes = WORK / "classes"
    classes.mkdir(parents=True)
    OUT.mkdir(parents=True)
    deps = [bridge.BML / "mods" / f / "helper.jar" for f in FRAMEWORKS]
    sources = sorted((HERE / "src").rglob("*.java"))
    classpath = ";".join(map(str, [bridge.COMPILE_JAR, bridge.LIBS_JAR, bridge.KIT / "bml-api.jar", *deps]))
    bridge.run(bridge.JDK / "javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-implicit:none",
               "-sourcepath", HERE / "src", "-cp", classpath, "-d", classes, *sources)
    print(bridge.run(bridge.JDK / "java", "-jar", bridge.KIT / "bml-modkit.jar", "finish", classes, WORK / "hooks.json",
                     bridge.BITWIG_JAR, bridge.LIBS_JAR, *deps).strip().splitlines()[-1])
    for extra in (HERE / "src").rglob("*"):
        if extra.is_file() and extra.suffix != ".java":
            dest = classes / extra.relative_to(HERE / "src")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(extra, dest)
    bridge.run(bridge.JDK / "jar", "--create", "--file", OUT / "helper.jar", "-C", classes, ".")
    source = json.loads((HERE / "mod.json").read_text("utf-8"))
    hooks = json.loads((WORK / "hooks.json").read_text("utf-8"))
    (OUT / "mod.json").write_text(json.dumps(bridge.finished_manifest(source, hooks), indent=2) + "\n", "utf-8")
    shutil.copy2(HERE / "README.md", OUT / "README.md")
    for item in (HERE / "package").iterdir():
        (shutil.copytree if item.is_dir() else shutil.copy2)(item, OUT / item.name)
    print(f"built {OUT}")


def check() -> None:
    for name in FRAMEWORKS:
        shutil.rmtree(CHECK / name, ignore_errors=True)
        shutil.copytree(bridge.BML / "mods" / name, CHECK / name)
    shutil.rmtree(CHECK / "openwig", ignore_errors=True)
    shutil.copytree(bridge.OUT, CHECK / "openwig")
    result = subprocess.run(["cmd", "/c", str(bridge.BML / "loader" / "prepare-mod.cmd"), str(bridge.BITWIG_BIN), str(CHECK)],
                            capture_output=True, text=True)
    lines = (result.stdout + result.stderr).strip().splitlines()
    print("\n".join(lines[-25:]))
    if result.returncode or not any(l.startswith("PREPARE_OK") for l in lines):
        raise SystemExit("offline check failed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="run Patchbay's offline check after building")
    args = parser.parse_args()
    build()
    if args.check:
        check()


if __name__ == "__main__":
    main()
