# AGENTS.md

Guidance for coding agents working in this repository.

## What this is

openwig (GitHub `Axyom/openwig`, public): two mods for the Patchbay mod loader (BML)
that let agents control Bitwig Studio from inside the running app.

- `mods/openwig/`: mod id `openwig`. In-process JSON-RPC bridge on `127.0.0.1:7778` plus
  an MCP server on `http://127.0.0.1:7779/mcp` (`McpServer.java`, Streamable HTTP, JSON
  responses, local only). Edits run on Bitwig's event queue, one undo step each.
  `porting/` holds the tools that ported the old 6.1.5 bridge to 6.1.7 names (FabricMC
  Matcher output, lookups, name tables); keep them for the next Bitwig version.
- `mods/openwig-agent/`: mod id `openwig-agent`. Agent panel inside Bitwig (runs the
  user's Claude Code through a gate on `127.0.0.1:7780/mcp` that asks Allow for edits),
  and builds/installs mods with a bundled ECJ (`package/tools/ecj-3.44.0.jar`, EPL-2.0,
  keep its NOTICE and about file). Requires `openwig` and `panel-management-framework`.

Targets Bitwig **6.1.7, Windows x64 only**, Patchbay 0.36.0-dev.17. The old Python SDK
and Java bridge ended at tag `python-sdk-final` (PyPI 0.1.3). `PROJECT_EDITING_PLAN.md`
is history of that design, not current work.

## Commands

```powershell
python build.py --release              # both mods + scratch/release/<mod-id>-<version>.zip
python build.py --check                # both mods + Patchbay's offline check (prepare-mod.cmd)
python mods/openwig/build.py [--check] [--release]
python mods/openwig-agent/build.py [--check] [--release]   # needs the openwig mod built first
```

Builds need JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`), the Patchbay
install (`%LOCALAPPDATA%\Programs\BML-0.36.0-dev.17-windows`, its `modkit/`), and
`scratch/bitwig617-compile.jar`, made by `bml-modkit compile-jar` from the local Bitwig
install. Patchbay's modder guide is `docs/sdk/building.md` in its install.

CI (`.github/workflows/ci.yml`) cannot build mods (no Bitwig jar on GitHub runners): it
byte-compiles the repo's Python scripts and validates `mod.json` / `build.json`.
`.github/workflows/release.yml` publishes the `pypi-moved/` stub on tags `pypi-v*` only
(PyPI trusted publishing is bound to that filename and the `pypi` environment). Mod
releases use tags `v*` and a GitHub release with one ZIP per mod.

## Constraints

- **Version lock.** Each `mod.json` has `built_against.bitwig_version`; both mods share one
  `mod.version`. Bump deliberately, never automatically.
- **CFR, not javap.** Inspect Bitwig classes with CFR 0.152 and existing decompiled sources
  under `scratch/decomp*/` first; do not dump raw `javap -c` bytecode (a short `javap -c`
  check of one overload is fine).
- **Never commit Bitwig code**: no Bitwig jars, no compile jar, no decompiled Bitwig sources,
  no Patchbay install files. They live in `scratch/` (gitignored) only.
- Never modify `C:\Program Files\Bitwig Studio` or the Patchbay install folder; build into
  `scratch/`.
- Live work uses a disposable project: several bridge methods wipe or close projects.
