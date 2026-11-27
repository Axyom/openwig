"""CI: every JSON file under mods/ parses, every mod has mod.json/build.json, ids match folders, and all mods share one version."""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
errors, versions = [], {}
mods = sorted(p for p in (REPO / "mods").iterdir() if p.is_dir())
if not mods:
    errors.append("no mods found under mods/")
for mod in mods:
    for name in ("mod.json", "build.json"):
        path = mod / name
        try:
            data = json.loads(path.read_text("utf-8"))
        except (OSError, ValueError) as exc:
            errors.append(f"{path.relative_to(REPO)}: {exc}")
            continue
        if name == "mod.json":
            info = data.get("mod", {})
            if info.get("id") != mod.name:
                errors.append(f"{path.relative_to(REPO)}: mod.id {info.get('id')!r} != folder {mod.name!r}")
            if not data.get("built_against", {}).get("bitwig_version"):
                errors.append(f"{path.relative_to(REPO)}: built_against.bitwig_version missing")
            versions[mod.name] = info.get("version")
            for res in data.get("resources", {}).values():
                if res.get("kind") in ("file", "directory") and not (mod / "package" / res["path"]).exists():
                    errors.append(f"{path.relative_to(REPO)}: resource {res['path']} not in package/")
for path in sorted((REPO / "mods").rglob("*.json")):
    try:
        json.loads(path.read_text("utf-8"))
    except ValueError as exc:
        errors.append(f"{path.relative_to(REPO)}: {exc}")
if len(set(versions.values())) > 1:
    errors.append(f"mods disagree on the version: {versions}")
print("\n".join(errors) or f"ok: {versions}")
sys.exit(1 if errors else 0)
