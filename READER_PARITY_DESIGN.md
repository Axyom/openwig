# Reader parity design: live JSON at DAWproject depth

Handoff design doc for the next model. Owner-approved 2026-10-18.
Read `AGENTS.md`, `java_bridge/README.md`, `JAVA_BRIDGE_GAPS.md` first; they
stay authoritative for what is verified. This doc is the plan for closing the
remaining read gaps.

## Goal

Make the live JSON read path (`JavaSong.dump()` and targeted reads) reach the
same depth as Bitwig's DAWproject export, natively in the Java bridge.
Two entry points over one implementation:

- **Atomic whole-project dump** (existing `project.dump`, one document event).
- **Targeted per-object reads** (new RPCs, polled on demand).

The owner does not need cross-call atomicity (no reads during editing), but
the single-event dump stays because it is free once reads share one code path.

DAWproject export stays as the archival format and the parity baseline.

## Current state (all verified live unless noted)

- Branch `codex/java-bridge-615`, HEAD `34cfa69` (2026-10-18). Public repo:
  work on this branch or new feature branches, never push `master`, no
  AI-attribution trailers in commits (owner rule).
- Verified live on the owner's released project
  `C:\Users\Axyom\Documents\Bitwig Studio\Projects\Finished\2025\Axyom - The Substance\Axyom - The Substance EXPERIMENT.bwproject`
  (referred to below as EXPERIMENT). Artifacts from those runs (gitignored,
  in `scratch/`): `substance-dump.json`, `substance.dawproject`,
  `dpx/project.xml` (4.4 MB), `substance-dump` summaries.
- `project.dump` returns: metadata (10 text fields), cue markers
  (time/name/color), transport (tempo, groove enabled, shuffle, current time
  signature), full nested track tree (groups + children, index, name, color,
  is_group, track_type, is_active, volume, pan, mute, crossfade, sends with
  target names, devices with kind native/vst3/vst2/plugin + enabled + active),
  arranger clips (name, color, start, duration, muted, loop info, play offset;
  note clips with per-note channel/key/start/duration/on+off velocity/muted;
  audio clips with fades), effect tracks, master.
- `project.export_dawproject` writes a `.dawproject` zip (project.xml +
  metadata.xml) via Bitwig's own `ApiProject.exportDawProject` +
  `DawProject.save`. Structure-only export of EXPERIMENT: 139 KB zip,
  4.4 MB XML, 107 tracks, 5729 clips, 2527 notes, 10 scenes, 5697 warp
  markers. `embed_media=True` embeds samples. XML tags observed: Arrangement,
  BuiltinDevice, Channel (routing destination, solo), Clip, ClipSlot, Devices,
  Enabled, Lanes, Marker, Note, Points/RealPoint, RealParameter, Scene,
  Tempo, TimeSignature, Track, Warp(s), File, plus mixer tags.
- `automation.enumerate` lists lanes per top-level track or master
  (mixer volume/pan/sends + native double parameters, point counts).
- Addressing today: main tracks 0..n-1 (top level only), -1 master,
  -2..-(n+2) effect tracks. Group children are NOT addressable.

## Architecture decisions (owner-approved)

1. **Path-string addressing for tracks.** `"master"` for the master track,
   `"fx/<name>"` for effect tracks, slash-separated group path otherwise,
   e.g. `"Drums/Kick Group/Kick"`. Segments are track titles as they appear
   in the dump. Devices, clips, notes, and parameters stay index-based
   (`device`, `clip` integer params) because chain/timeline order is itself
   the datum. Duplicate sibling names: resolve to the first match; a later
   iteration may add `Name#2` disambiguation if the owner hits it.
2. **Polling only.** No push notifications / observers in scope (JS observer
   path was flaky). Targeted reads are request/response.
3. **One implementation, two entry points.** Java per-object read functions;
   the monolithic dump composes them inside one document event; targeted RPCs
   expose them individually.
4. **Parity harness gates every phase.** Each phase is done when the harness
   diff shrinks as specified, live checks pass, and docs are updated.

## Phase 1 — Python converter + parity harness (no Java changes)

New module `src/openwig/java_read.py`:

- `export_to_dict(song, path=None) -> dict`: calls
  `song.export_dawproject(path or temp file)`, unzips, parses `project.xml`
  with `xml.etree.ElementTree` into a dict (attributes kept, children as
  lists keyed by tag, `id` attributes preserved). Temp files under
  `tempfile`, cleaned up.
- `parity_report(dump: dict, daw: dict) -> dict`: machine-readable checklist.
  Sections: tracks, groups, devices, clips, notes, automation, routing,
  scenes/launcher, markers, transport, metadata. For each section: counts on
  both sides (must match exactly for structure), field coverage
  (native / partial / export-only), and a flat list of missing-native fields.
  Structure-count mismatches are reported loudly (they mean a reader bug,
  not a depth gap).

Committed fixture: a trimmed-down `tests/fixtures/dawproject-sample/`
(project.xml + metadata.xml, small, hand-cut from a synthetic project built
via `JavaSong`, NOT the owner's released content) plus unit tests for
`export_to_dict` parsing and `parity_report` logic. Do not commit any
EXPERIMENT-derived XML (it is the owner's release).

The field checklist for later phases comes from two sources, cross-checked:
the observed XML, and the JAXB classes in `libs.jar` under
`com/bitwig/dawproject/` (they ARE the schema; `javap` them for the full
field list, including plugin device representation which this project barely
exercises).

Deliverable: harness output for EXPERIMENT committed to nothing (scratch),
but its summary pasted into `JAVA_BRIDGE_GAPS.md` as the numbered backlog.

## Phase 2 — API tier: targeted reads + cheap fields (Java)

Refactor `OpenwigDocument`:

- `private static TrackOrTrackGroup resolve(ApiProject, String path)` —
  walks `mainTracks()` by title, descends groups, handles `"master"` and
  `"fx/<name>"`. Throws `IllegalArgumentException` with the full path on a
  missed segment.
- Split current dump builders into per-object functions that take the
  already-resolved objects (no `project()` inside):
  `readTrackOrGroup(item, path)`, `readClip(clip)`, `readDeviceList(view)`,
  `addLane(...)` (already exists). `dumpProject()` keeps its shape but calls
  these and now also emits each track's `path`.
- New/extended reads (API-verified signatures only):
  - Launcher clip slots: `Track.clipSlot(int).getClip()` + `hasStopButton()`.
    Slot count is not exposed; probe `getScene(i)`/`clipSlot(i)` upward with
    `RuntimeException` caught per index, capped (e.g. 1024), and record the
    discovered scene count in the dump. If probing misbehaves on the exact
    build, drop to slot-read-on-demand only and note it.
  - Scenes: `ApiProject.getScene(int)` title/color into a top-level
    `scenes` array.
  - Send modes: `Send.getSendMode()`/`getEffectiveSendMode()` names.
- New RPCs in `Bootstrap` + `PatchMain` adapters + `DirectAccess` stub
  (three places, keep in sync):
  - `track.read {path}` -> one track subtree (same shape as dump nodes).
  - `clip.read {path, clip}` -> one clip.
  - `device.read {path, device}` -> device info incl. parameters when native
    (reuse `listDeviceParameters` + `get_parameters` shapes).
  - `automation.enumerate {path}` now path-addressed (subsumes index form).
- Python: `JavaTrack` gains path-aware helpers; `JavaSong.track_at(path)`.
- Parity gate: sections `scenes/launcher`, `sends` move to native; structure
  counts still match.

## Phase 3 — Model tier: true native parity (Java, reverse engineering)

Per item: work via `((ApiObject) x).getTarget()` into the exact-build model
classes; find getters with `javap`/CFR from the ORIGINAL jar
(`bitwig.jar.openwig-original-6.1.5`, hash `d56248d0...`); add exact
signatures as stubs; verify each read live on EXPERIMENT; confirm the parity
diff shrinks; one item per build/live cycle. Order (owner-approved):

1. **Plugin identity + parameters.** Device id/name/vendor/preset for
   VST2/VST3/CLAP; parameter name/value lists. Start from the model objects
   behind `PluginDevice`/`Vst3Device`; cross-check against the XML the
   exporter emits for the same device.
2. **Routing.** Audio/note source + destination, sidechain source, pre/post
   taps (`Track.sourceSelector()` etc. and their model targets). The old
   sidechain blocker (opaque inport_connections) was for WRITING; reading
   may be fine. Do not invoke any engine-graph code (BFS crash history).
3. **Note extras.** Note expression values (API is write-only: read from the
   model), chance, repeats, occurrence, recurrence.
4. **Audio clip internals.** Sample file reference (path), warp markers
   (`WarpMarkers`), warp mode/grain, stretch settings, reverse, pitch/gain.
5. **Device chain nesting.** Layers/FX layers, drum pads, note FX chains,
   instrument selector slots.
6. **Modulators (gated).** Read-only model getters only, no engine calls,
   scratch copy only. Any crash, hang, or engine restart ends the item:
   mark unfixable-at-this-depth and leave it to the export path.

Performance note: per-parameter reflection walks (`walkAtoms`) are the
expensive part; cache walk results per device within a single dump/enumerate
call (IdentityHashMap keyed by device target), never across calls.

## Phase 4 — Corpus + recreate (separate doc later)

Batch capture over the owner's project catalog (open each in the test copy,
dump + export, close WITHOUT saving; never auto-save), derived features
(keep raw exports separate), and the `ApiProject.importDawProject` write
path. Out of scope here; do not start it under this doc.

## Verification protocol (every phase, in order)

1. `javap`-verify every new called signature against the original jar
   BEFORE building (stubs are compile-time only; mismatches become
   NoSuchMethodError at runtime).
2. `python java_bridge\build.py --bitwig-root scratch\bitwig-6.1.5-live --output scratch\bitwig-6.1.5-openwig.jar`
   (builds from the retained original backup; restore before install).
3. `python java_bridge\smoke.py`; `python -m pytest -q --basetemp scratch\pytest-<name> -p no:cacheprovider`.
4. Stop test-copy processes (wait for exit), `install.py restore` then
   `install ... --assume-closed`, relaunch with EXPERIMENT, wait for
   `document.status.active` + ~12 s settle.
5. Live-verify new reads; run the parity harness; diff must shrink as
   specified. NEVER save the project; the reader is read-only.
6. Update `java_bridge/README.md` (verified claims only) and
   `JAVA_BRIDGE_GAPS.md`; commit and push the branch. Do not run
   `git add -A` blindly after live runs; scratch/ is ignored but check
   `git status` first.

## Hard constraints

- One patched Bitwig at a time (port 7778). The owner's Program Files
  install is patched too (backup beside it); prefer the scratch copy
  `scratch\bitwig-6.1.5-live` so no admin rights are ever needed.
- Never save over the owner's projects. `project.clear`/`JavaSong.clear()`
  only on disposable projects.
- No new work on variable tempo or launcher-clip writing (owner rulings).
- JS controller bridge (port 7777) is untouched by this effort.
- Every "verified" claim in README must trace to a live run; keep
  pending/verified wording honest.
