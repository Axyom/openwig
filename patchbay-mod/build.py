"""Build the OpenWig Patchbay mod (Patchbay docs/sdk/building.md, steps 1-8).

    python patchbay-mod/build.py [--check]

Writes the installable folder to scratch/patchbay-out/openwig. --check then runs
Patchbay's offline check (loader/prepare-mod.cmd) on it, which reads Bitwig's
install and modifies nothing there.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
JDK = Path(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin")
BML = Path.home() / "AppData" / "Local" / "Programs" / "BML-0.36.0-dev.17-windows"
KIT = BML / "modkit"
BITWIG_BIN = Path(r"C:\Program Files\Bitwig Studio\bin")
BITWIG_JAR = BITWIG_BIN / "bitwig.jar"
LIBS_JAR = BITWIG_BIN / "libs.jar"
COMPILE_JAR = REPO / "scratch" / "bitwig617-compile.jar"   # made from your own install: never commit or share
WORK = REPO / "scratch" / "patchbay-build"
CHECK = REPO / "scratch" / "patchbay-out"
OUT = CHECK / "openwig"


def run(*cmd: object) -> str:
    result = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode:
        raise SystemExit(f"step failed: {cmd[0]}\n{output}")
    return output


def target_id(target: dict) -> str:
    """building.md, "The finished mod.json", rule 2."""
    raw = target["class"] if target["kind"] == "class" else f"{target['owner']}.{target['name']}"
    raw = raw.replace("/", ".").replace("$", "_")
    return re.sub(r"[^A-Za-z0-9_.-]", "", raw)


def finished_manifest(source: dict, hooks: dict) -> dict:
    manifest = dict(source)
    targets: dict[str, dict] = {}

    def add(target: dict) -> str:
        for key, value in targets.items():
            if value == target:
                return key
        base = key = target_id(target)
        n = 2
        while key in targets:
            key, n = f"{base}-{n}", n + 1
        targets[key] = target
        return key

    out_hooks = []
    for hook in hooks.get("hooks", []):
        entry = {"id": hook["id"], "target": add(hook["target"]), "transform": hook["transform"]}
        if "returns" in hook:
            entry["returns"] = hook["returns"]
        if "method_from" in hook:
            entry["method"] = {"from_target": add(hook["method_from"])}
            entry["body"] = {"template": hook["body"]}
        if "match" in hook:
            match = hook["match"]
            if "target" in match:
                entry["match"] = {"opcode": match["opcode"], "target": add(match["target"]),
                                  "occurrence": match["occurrence"]}
            else:
                entry["match"] = {k: match[k] for k in ("opcode", "owner", "name", "descriptor", "occurrence")
                                  if k in match}
        entry["bridge"] = hook["bridge"]
        out_hooks.append(entry)
    fields = []
    for field in hooks.get("fields", []):
        owner = add({"kind": "class", "class": field["owner"]})
        for cls in re.findall(r"L([^;]+);", field["descriptor"]):
            if not cls.startswith("java/"):
                add({"kind": "class", "class": cls})
        fields.append({"id": field["id"], "owner": owner, "name": field["name"], "descriptor": field["descriptor"]})
    opens = sorted(add(t) for t in hooks.get("access", []))
    if hooks.get("hook_classes"):
        manifest["helper"] = {"jar": "helper.jar", "class": hooks["hook_classes"][0]}
    manifest["targets"] = targets
    manifest["hooks"] = out_hooks
    if fields:
        manifest["fields"] = fields
    if opens:
        manifest["opens"] = opens
    order = ["manifest_version", "mod", "built_against", "helper", "targets", "hooks", "fields", "opens"]
    return {k: manifest[k] for k in order if k in manifest} | {k: v for k, v in manifest.items() if k not in order}


def build() -> None:
    shutil.rmtree(WORK, ignore_errors=True)
    shutil.rmtree(OUT, ignore_errors=True)
    classes = WORK / "classes"
    classes.mkdir(parents=True)
    OUT.mkdir(parents=True)
    if not COMPILE_JAR.exists():
        print(run(JDK / "java", "-jar", KIT / "bml-modkit.jar", "compile-jar", BITWIG_JAR, COMPILE_JAR).strip())
    sources = sorted((HERE / "src").rglob("*.java"))
    classpath = ";".join(map(str, [COMPILE_JAR, LIBS_JAR, KIT / "bml-api.jar"]))
    run(JDK / "javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-implicit:none",
        "-sourcepath", HERE / "src", "-cp", classpath, "-d", classes, *sources)
    print(run(JDK / "java", "-jar", KIT / "bml-modkit.jar", "finish", classes, WORK / "hooks.json",
              BITWIG_JAR, LIBS_JAR).strip().splitlines()[-1])
    for extra in (HERE / "src").rglob("*"):
        if extra.is_file() and extra.suffix != ".java":
            dest = classes / extra.relative_to(HERE / "src")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(extra, dest)
    run(JDK / "jar", "--create", "--file", OUT / "helper.jar", "-C", classes, ".")
    source = json.loads((HERE / "mod.json").read_text("utf-8"))
    hooks = json.loads((WORK / "hooks.json").read_text("utf-8"))
    (OUT / "mod.json").write_text(json.dumps(finished_manifest(source, hooks), indent=2) + "\n", "utf-8")
    shutil.copy2(HERE / "README.md", OUT / "README.md")
    print(f"built {OUT}: {len(hooks.get('hooks', []))} hook(s), {len(hooks.get('access', []))} opened member(s)")


def check() -> None:
    result = subprocess.run(["cmd", "/c", str(BML / "loader" / "prepare-mod.cmd"), str(BITWIG_BIN), str(CHECK)],
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
