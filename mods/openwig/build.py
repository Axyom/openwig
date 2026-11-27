"""Build the OpenWig Patchbay mod (Patchbay docs/sdk/building.md, steps 1-8).

    python mods/openwig/build.py [--check] [--release]

Writes the installable folder to scratch/patchbay-out/openwig. --check then runs
Patchbay's offline check (prepare-mod.cmd, from a private loader copy in
scratch/patchbay-check) on it; it reads Bitwig's and Patchbay's installs and
modifies neither. --release also writes the ZIP to attach to a
GitHub release, scratch/release/openwig-<version>.zip (the installed folder plus src/
and build.json); users install it with Patchbay's Add Mod.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
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
RELEASE = REPO / "scratch" / "release"


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


def build_mod(here: Path, work: Path, out: Path, deps: tuple[Path, ...] = (), package: bool = False) -> None:
    """Steps 1-8 for the mod in here: compile against Bitwig, the kit and deps (other mods' helper jars), finish,
    write helper.jar and the finished mod.json to out, plus README.md and, with package, the package/ folder."""
    shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(out, ignore_errors=True)
    classes = work / "classes"
    classes.mkdir(parents=True)
    out.mkdir(parents=True)
    if not COMPILE_JAR.exists():
        print(run(JDK / "java", "-jar", KIT / "bml-modkit.jar", "compile-jar", BITWIG_JAR, COMPILE_JAR).strip())
    sources = sorted((here / "src").rglob("*.java"))
    classpath = ";".join(map(str, [COMPILE_JAR, LIBS_JAR, KIT / "bml-api.jar", *deps]))
    run(JDK / "javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-implicit:none",
        "-sourcepath", here / "src", "-cp", classpath, "-d", classes, *sources)
    print(run(JDK / "java", "-jar", KIT / "bml-modkit.jar", "finish", classes, work / "hooks.json",
              BITWIG_JAR, LIBS_JAR, *deps).strip().splitlines()[-1])
    for extra in (here / "src").rglob("*"):
        if extra.is_file() and extra.suffix != ".java":
            dest = classes / extra.relative_to(here / "src")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(extra, dest)
    run(JDK / "jar", "--create", "--file", out / "helper.jar", "-C", classes, ".")
    source = json.loads((here / "mod.json").read_text("utf-8"))
    hooks = json.loads((work / "hooks.json").read_text("utf-8"))
    (out / "mod.json").write_text(json.dumps(finished_manifest(source, hooks), indent=2) + "\n", "utf-8")
    shutil.copy2(here / "README.md", out / "README.md")
    if package:
        for item in (here / "package").iterdir():
            (shutil.copytree if item.is_dir() else shutil.copy2)(item, out / item.name)
    print(f"built {out}: {len(hooks.get('hooks', []))} hook(s), {len(hooks.get('access', []))} opened member(s)")


def build() -> None:
    build_mod(HERE, WORK, OUT)


LOADER_COPY = REPO / "scratch" / "patchbay-check"
LOADER_PARTS = ["api", "app", "jvmti", "tools", "find-java.cmd", "prepare-mod.cmd", "mod.schema.json", "mod-v3.schema.json"]


def offline_check(mods_root: Path) -> None:
    """Patchbay's preparer on mods_root, run from a private loader copy: the preparer writes into its loader
    folder, so running the installed one would change the Patchbay install (and fails while Bitwig runs)."""
    loader = LOADER_COPY / "loader"
    if not (loader / "app" / "bml-control.jar").exists():
        for part in LOADER_PARTS:
            src = BML / "loader" / part
            if src.is_dir():
                shutil.copytree(src, loader / part, dirs_exist_ok=True)
            elif src.exists():
                loader.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, loader / part)
    maps = LOADER_COPY / "maps"
    if not maps.exists():
        run("cmd", "/c", "mklink", "/J", maps, BML / "maps")
    # one string with outer quotes: cmd /c strips the first and last quote when the line has spaces in paths
    command = f'cmd /c ""{loader / "prepare-mod.cmd"}" "{BITWIG_BIN}" "{mods_root}""'
    result = subprocess.run(command, capture_output=True, text=True)
    lines = (result.stdout + result.stderr).strip().splitlines()
    print("\n".join(lines[-25:]))
    if result.returncode or not any(l.startswith("PREPARE_OK") for l in lines):
        raise SystemExit("offline check failed")


def check() -> None:
    offline_check(CHECK)


def release(here: Path = HERE, out: Path = OUT) -> Path:
    """building.md step 8 for a release: the installed folder plus src/ and build.json, zipped with the folder on top."""
    mod = json.loads((here / "mod.json").read_text("utf-8"))["mod"]
    mod_id, version = mod["id"], mod["version"]
    stage = RELEASE / "stage"
    shutil.rmtree(stage, ignore_errors=True)
    shutil.copytree(out, stage / mod_id)
    shutil.copytree(here / "src", stage / mod_id / "src")
    shutil.copy2(here / "build.json", stage / mod_id / "build.json")
    RELEASE.mkdir(parents=True, exist_ok=True)
    archive = Path(shutil.make_archive(str(RELEASE / f"{mod_id}-{version}"), "zip", root_dir=stage, base_dir=mod_id))
    shutil.rmtree(stage)
    print(f"release {archive} ({archive.stat().st_size} bytes)")
    return archive


def cli(doc: str, build, check, release) -> None:
    """The --check / --release command line every build script shares: build, then check and release if asked."""
    parser = argparse.ArgumentParser(description=doc)
    parser.add_argument("--check", action="store_true", help="run Patchbay's offline check after building")
    parser.add_argument("--release", action="store_true",
                        help="also write scratch/release/<mod-id>-<version>.zip for GitHub Releases")
    args = parser.parse_args()
    build()
    if args.check:
        check()
    if args.release:
        release()


if __name__ == "__main__":
    cli(__doc__, build, check, release)
