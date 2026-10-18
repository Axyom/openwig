# AGENTS.md

This file provides guidance to coding agents working in this repository.

## Active goal: full project editing

Before working on project-editing functionality, read
[`PROJECT_EDITING_PLAN.md`](PROJECT_EDITING_PLAN.md). It is the authoritative
progress and handoff document for the owner's active goal: complete programmatic
Bitwig project editing. Start at **Current checkpoint: start here** for verified
progress, known issues, live-test setup, and the next concrete actions.

Update that document as each capability lands and before handing work to another
agent: record evidence, commit/CI status, remaining gaps, uncommitted work, and
the exact project currently open. Its owner-authorized editing scope supersedes
older reader-only exclusions; all original-project and exact-build safeguards
still apply. Do not mark the overall goal complete while documented gaps remain.

## What this is

openwig (PyPI `openwig`, GitHub `Axyom/openwig`, public): a Python SDK that builds Bitwig Studio songs (tracks, devices, MIDI clips, automation, master chain) and renders them to `.wav`. It goes beyond Bitwig's official Controller API by reaching into Bitwig's private, obfuscated internals. Targets Bitwig 6.0.6 on Windows (Linux experimental), Python 3.11+.

## Commands

```bash
pip install -e .[dev]                  # package + pytest + mkdocs-material
pytest                                 # unit tests (only tests/ is collected; live tests auto-skip)
pytest tests/test_api.py::test_name    # single test
OPENWIG_LIVE=1 pytest -m live          # live tests: need running Bitwig with OpenwigBridge enabled
node --check src/openwig/controller/openwig_bridge.control.js   # controller syntax (CI runs this)
mkdocs serve                           # docs site (docs/, mkdocs.yml)

python -m openwig install [--force]    # copy controller + symbols_default.json into Bitwig user dir
```

CI (`.github/workflows/ci.yml`): pytest on ubuntu + windows, py3.11/3.12, plus `node --check` on the controller. `tests/live_smoke*.py` are manual scripts, not collected tests.

Java bridge (Windows, JDK 21 `javac`, exact Bitwig 6.1.5 build):

```powershell
python java_bridge\smoke.py                                         # offline JAR diff + protocol check
python java_bridge\build.py --output scratch\bitwig-6.1.5-openwig.jar
python java_bridge\install.py install|status|restore [--bitwig-root <copy>]
```

Java reverse engineering uses CFR 0.152 (`scratch/tools/cfr.jar`). Always check `scratch/decomp/` first, and decompile classes from the backup JAR using `--extraclasspath` (because `.openwig-original-6.1.5` does not end in `.jar`):

```powershell
java -jar scratch\tools\cfr.jar --extraclasspath scratch\bitwig-6.1.5-live\bin\bitwig.jar.openwig-original-6.1.5 <fully.qualified.ClassName> --outputdir scratch\decomp
```

## Architecture

Two independent transports into Bitwig, both speaking JSON-RPC 2.0 to `openwig.bridge.BridgeClient`:

1. **Controller bridge (production, port 7777).** `src/openwig/controller/openwig_bridge.control.js` is a Bitwig controller script (GraalJS, `loadAPI(18)`) acting as the TCP server. Methods are entries in its `HANDLERS` table (`track.*`, `device.*`, `clip.*`, `automation.*`, `transport.*`, ...). Framing is asymmetric: Python to Bitwig is 4-byte big-endian length prefix + JSON (`bridge._frame`); Bitwig to Python is newline-delimited JSON. `BridgeClient` auto-reconnects in a background thread and handles pushed notifications (e.g. `connected`, op completion).

2. **Java bridge (experimental, port 7778, `java_bridge/`).** Bytecode-patches `BitwigStudioMain.main` (ASM, `PatchMain.java`) in a copy of `bitwig.jar` locked to one SHA-256, to start `Bootstrap` (in-process JSON-RPC server). `OpenwigDocument.java` performs document edits on Bitwig's event queue; `stubs/` are compile-time signatures only, and `DirectAccess`/`OpenwigUndo` are replaced with generated exact-build bytecode at build time. Edits that need it are wrapped in a Bitwig undo transaction. Python surface is `openwig.java_song.JavaSong`, a deliberately small subset. Only add operations here after a live test passes; `java_bridge/README.md` records what has been verified and must be kept in sync.

### Python layers

- `song.py`: `Song` / `Track` / `Note`, the public composition API. Thin by design: no note/curve generators. Translates calls into controller requests; paces document-thread ops via the pushed `op_done` notification rather than polling (concurrent JS during an op crashes GraalJS).
- `wire/`: low-level helpers. `automation.py` (value scaling), `render.py` (real-time loopback capture: WASAPI via PyAudioWPatch on Windows, `parecord` on Linux).
- `read/` + `recreate.py`: read the open project live (structure, clips via descriptor-graph walk) and emit a Python script that recreates it.
- `cli/`: install/uninstall/version.

### Tests

Unit tests use a `FakeBridge` that records `(method, params)` and returns scripted snapshots, so they check the SDK's half of the wire contract only. `conftest.py` puts `src/` on `sys.path` and provides a `live_bridge` fixture that skips if no Bitwig connects within ~6s.

## Constraints

- **Always use CFR (`scratch/tools/cfr.jar --extraclasspath ...`) and existing decompiled sources in `scratch/decomp/`** when inspecting Bitwig classes; do not waste tokens dumping raw `javap -c` bytecode.
- The controller's obfuscated names live only in `symbols_default.json` (data, no per-build resolver anymore): `paths.data_dir` is duplicated in the JS `_resolveLogPath`/`_defaultsPath` and must stay in lockstep.
- Supported Bitwig versions are locked in `pyproject.toml` `[tool.openwig]` and `openwig.SUPPORTED_BITWIG_VERSIONS`; the handshake refuses mismatches. Bump deliberately, never automatically. Version also lives in both `pyproject.toml` and `openwig/__init__.py`.
- `scratch/` is gitignored and excluded from test collection; use it for local probes and build outputs. Root-level `*.wav` files are render outputs, also ignored.
- Live work should use a disposable project: several operations (`project.clear`, `JavaSong.clear()`) wipe all tracks.
