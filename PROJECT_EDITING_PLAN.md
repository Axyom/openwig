# Full project editing: active goal and agent handoff

Last updated: 2026-10-19. Goal is **active**, not complete.

## Owner request and authorization

The owner asked for complete programmatic project editing after the remaining
gaps were explained: empty-time insertion, arbitrary audio-clip editing,
plugin insertion/control, launcher writes, Grid editing, and JSON-only project
reconstruction. Their instruction was: **“Then this is your new goal. Go !”**
They also explicitly requested this progress/handoff document. Continue the
implementation; do not interpret a request for status or documentation as a
replacement for the goal. Keep this file updated as work lands.

This extends the older reader-only scope in `READER_PARITY_DESIGN.md` and
`JAVA_BRIDGE_GAPS.md`. Restrictions there excluding these editing features are
historical scope boundaries, superseded by this owner request. Corpus collection
and unrelated NerDaw work remain separate.

## Repository and working rules

- Repository: `C:\Users\Axyom\Documents\git_repositories\bitwig-sdk`, public
  GitHub `Axyom/openwig`. Feature branch: `codex/java-bridge-615`, PR #5.
- Read `AGENTS.md`, `java_bridge/README.md`, and the local
  `.agents/skills/bitwig-decompile/SKILL.md` before extending the bridge.
- Target the exact Windows Bitwig **6.1.5** build. Original JAR SHA-256:
  `d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49`.
- CFR 0.152 only for decompilation. Check `scratch/decomp/` and
  `scratch/decomp_cfr/` first; new decompiled sources belong in `scratch/decomp/`.
  Never commit decompiled code, owner projects, samples, dumps, or recordings.
- All native document operations run on Bitwig's application event queue.
  Use native undo transactions; do not nest a second transaction around a native
  command that already owns one. Keep unknown runtime data explicit.
- Live mutations use a disposable project or a separate project copy. Never
  clear or save over the owner's original project. Identify a project by its
  absolute path and select it explicitly; do not assume the first tab is correct.
- Commit and push tested increments on the feature branch. Never push master.
  The owner previously explicitly requested commit/push and dislikes abandoned
  uncommitted changes. Do not add AI attribution trailers.
- Do not spawn subagents unless the owner or applicable instructions authorize
  delegation. No such authorization currently exists for this goal.

## What is already verified

- Composition subset: nested groups, instrument/audio/hybrid tracks, native
  factory devices, numeric/boolean/enum native parameters, arranger MIDI notes,
  mixer and several routing operations, metadata, markers, scenes, transport,
  arranger automation, and real-time loopback WAV capture.
- Deep native reads: tracks, clips, note expressions, audio events/warps,
  recursive device chains, sidechain sources, modulators/bindings, and arranger
  automation. This remains a partial reader schema, not a complete editable one.
- Native save/capture/restore: `save_project`, `export_project`, offline
  `write_project`; collected media, embedded plugin states, manifest hashes,
  fresh destinations, and explicit open-project selection. See
  `JAVA_PROJECT_WRITE_DESIGN.md` for format and evidence.
- Full native roundtrip gate: 107 tracks, 978 recursive devices, 2831 clips,
  2527 notes, 2898 audio events, 81 collected media files, 120 unique embedded
  plugin-state blobs. Zero reader-visible differences under documented
  cache/audio-hash comparison rules. Original source file unchanged.
- `duplicate_range(start, end)`: native Duplicate and Push, in beats. Moves
  later clips, automation, and cue markers; splits crossing clips natively.
  Disposable gate: clips at 0/8 became 0/4/12 when duplicating 0..4; marker
  and automation at 8 moved to 12.
- Musical use of range duplication: repeated 304..336 twice in a full project
  copy, added a 51-note C-minor Polysynth part, shifted the vocal 336 -> 400,
  END 792 -> 856, and retained all 107 existing tracks before adding the synth.
  The saved variation reopened with 108 tracks, its notes and automation intact;
  a final preview was recorded from the reopened file, with no clipped samples.

Baseline implementation commits (use `git log` for subsequent increments):

- `634d28f`: portable native project writing, archive tests, live roundtrip.
- `7eb8492`: arranger Duplicate and Push; **106 unit tests**, Java offline
  smoke, and all five GitHub CI jobs passed.

## Implementation sequence and progress

Use a separate verified increment for each meaningful operation. Mark an item
complete only after its live test, regression coverage, docs and commit exist.
Do not equate native snapshot preservation with arbitrary field editing.

1. **Arranger and existing clips — in progress.**
   - Done: duplicate range with push.
   - Done: `insert_time(start, beats)` and `delete_range(start, end)`, including
     nested crossing clips, effect/master automation, cue markers, and persistence.
   - Done: nonzero-start automation anchor handling; hold curvature canonicalization.
   - Implemented: move/resize/trim/mute/name/color/loop/fade and delete existing clips.
   - Next: native clip copy/split; replace/edit MIDI content and expressions.
   - Verify clips crossing edit boundaries, nested tracks, effect/master
     automation, markers, invalid input, and save/reopen persistence.
2. **Audio creation and editing — pending.**
   - Implemented: local RAW sample import, source offsets, container fades, gain.
   - Pending: audio-event edits, pitch/reverse,
     stretch/warp settings and markers, event replacement/deletion.
   - Use authored synthetic audio fixtures. Verify timing and audible output,
     media collection, and save/reopen; never use track counts as sole proof.
3. **Plugins, nested device chains, and routing — pending.**
   - Discover installed plugin identities; insert supported formats; load/save
     state; read/write parameters and automation; manipulate nested chains.
   - Complete routing and modulation edits, documenting actual format coverage.
     Resident plugin caches may be unavailable; never invent values.
4. **Launcher and scene editing — pending.**
   - Create/replace/delete filled slots, launch/stop behavior, scene edits,
     note/audio content, launch settings, save/reopen tests.
   - Slot getters can CREATE missing slots; validate bounds before discovery.
5. **Grid graph and modulation editing — pending research.**
   - Existing reader exposes stored atoms, not module/cable topology.
   - Inspect native serialization/model operations with CFR and a deliberately
     small disposable Grid fixture. Add module/port/cable/parameter operations
     only after successful live tests. Report crashes or missing support honestly.
6. **Documented JSON reconstruction — pending.**
   - Define a versioned, validated editable schema covering the capabilities
     above, stable references, media and plugin/opaque state resources.
   - Reconstruct a newly authored project from that representation, compare
     values, render, save and reopen. Missing/unrepresentable state must fail
     explicitly or be declared; never silently drop fields.
   - Opaque native archive restoration alone does not satisfy this milestone.
7. **Completion audit — pending.**
   - Publish a capability inventory mapping every promised operation to a
     public method, regression/live evidence, limitations, and documentation.
   - Exercise a full authored project and a separate real-project copy.
     All required checks green, changes pushed, working tree accounted for.

## Current checkpoint: start here

Latest increment: generic arranger clip updates/deletion and local RAW audio
import, public `JavaTrack.update_clip`, `delete_clip`, and `import_audio`.
The native edits run in undo transactions, preserve underlying note/audio
content, maintain sorted positions, and reject overlaps before mutation. The
manual gate checks move/resize, name/mute/color/loop/play offset, audio fades,
source offsets in seconds, gain in decibels, rejected edits, deletion, media
collection, playback, and save/reopen. Audio event/container times are beats;
RAW source offsets and expression domains are seconds. Resize trims the
container; it does not scale or rewrite its content events.

Saving exposed an older assumption: serialization drops unused media entries
(e.g. after clearing/deleting audio), so source and cloned indexes cannot be
paired. The saver now matches persisted media identities (name, relative and
absolute locations, package identity, length), fails on ambiguity, and collects
only the clone's referenced media. Source files are resolved from the live
source, never by guessing relocated clone paths.

Evidence is in `scratch/editing-clip-gate-5/`; reusable manual gate:
`tests/live_java_clips.py`. Run mutation with `--disposable --project <open file>
--output <fresh directory> [--render]`, then reopen its saved project and run
`--verify` with that saved project's exact path and the same output directory.
Final local validation: 130 unit tests passed, exact-build Java build and
JAR/protocol smoke passed, controller syntax and diff whitespace checks passed.
Mutation/playback and reopened/playback gates both passed; reopened reader audit
has zero differences. The reopened recording measured 440.023 Hz and source-tone
amplitude 0.00266. A second save from collected relative media succeeded.
The raw loopback's total RMS also includes unrelated tones; pitch testing needs
isolation. Implementation is ready to commit/push; CI status will be recorded
at the next checkpoint.

Current scratch project: `scratch/editing-clip-gate-5/saved/project.bwproject`,
stopped. Last observed scratch launcher PID 66136; re-identify before any process
operation. Second collected save: `scratch/editing-clip-gate-5/resaved/project.bwproject`.
No live tool sessions remain running.

**Pitch remains pending.** Initial RAW import stored transpose but playback
ignored it. Experimental STRETCH/ELASTIQUE_PRO warp/pitch variants had valid
readback and save/reopen, but loopback included other tones even while Bitwig
was stopped. A first apparent pitch pass was not reproducible. Transpose and
warp writes are deliberately absent from the public import API and the native
RPC rejects a transpose field; do not claim verified audible transposition.
CFR sources for WarpMode/Markers and implementations are available under
`scratch/decomp/editing/`. Stop unrelated audio before the next pitch gate, or
use an isolated native render path. The owner had muted the output earlier;
unmuting restored audible capture. Do not repeat broad endpoint troubleshooting.

Prior completed increment `2fad08f`: empty-time insertion, delete-and-pull,
nonzero-start automation and native hold-curve canonicalization. All five CI
jobs passed; 107 baseline tests. Range persistence report:
`scratch/editing-range-gate-2/persistence.json`, zero differences. Transport loop
bounds intentionally require explicit caller adjustment.

The scratch instance is on the synthetic clip fixture (see final checkpoint
entry below). No owner project was edited. Preserve the saved user musical
variation at `scratch/music-variations/substance-v1/project/`.

Immediate next actions:

1. Finish any recorded pending validation/commit below, keep CI and this handoff
   accurate. The overall goal remains active.
2. Native clip copy/split, MIDI content replacement and note-expression edits.
3. Audio-event editing/reverse/warps and properly isolated pitch playback proof.
4. Continue plugin/nested routing, launcher, Grid and JSON reconstruction phases.

## Exact-build research already available

Range operations, decompiled in `scratch/decomp/variation/`:

- `pZb.mjf().wtf(List<zJD>, start, end, Lsv)`: Duplicate and Push (already used).
- `pZb.mjf().Gcr(List<zJD>, start, end, Lsv)`: insert empty arranger time,
  length `end - start`. **Live-tested, exposed as `insert_time`.**
- `pZb.mjf().AOA(List<zJD>, start, end, Lsv)`: delete range and pull later
  content left. **Live-tested, exposed as `delete_range`.**
- Root selection: `oZU.Qjx().bV_()`; editor selection:
  `oZU.qVp().TcG().tY1()`. The native commands own their transactions.
- Native insert traverses clip/automation timelines using
  `XCv.IYM(start, length, fKr, Set)` and moves cue markers when the root is selected.

New interface sources under `scratch/decomp/editing/`:

- `Clip`, `AudioClip`, `NoteClip`, `EventTimeline`, `ClipTimeline`, `Factory`,
  `AudioNote`, `ClipContent`, `NoteOrClip`, `SampleReference`, and `ApiClip`.
- `Clip`: title/color, play-start offset, loop start/duration/enabled,
  quantization, launch mode and next action. Time/duration/mute are inherited.
- `AudioClip`: fade-in/out setters/getters. `ClipContent.getEventTimeline()`
  exposes its note/audio event timeline; `EventTimeline` has add/get/clearTime.
- `Factory.createAudioClip(boolean)`,
  `createSampleReferenceForLocalFile(File)`,
  `createAudioNote(double, double, SampleReference)` are available. Inspect
  their implementations to establish units and semantics before using them.
- `AudioNote`: warp modes/events, transients, grain/tail mode, formants,
  fades, file/rate/channels/duration. More event properties are inherited.
- `ApiClip.setTime` ultimately uses native event setters; attached timeline
  sorting and overlap behavior must be tested before exposing clip moves.
- `ApiAudioClip` is not a class in this build. The actual implementation is
  `ApiAudioNoteClipEvent`; its content wrapper is `ApiAudioNoteClipContentTimeline`.
  These and `ApiAudioNoteEvent`, `ApiEnduringEvent`, `NoteExpression` are now
  decompiled under `scratch/decomp/editing/`. Audio event gain/pan/transpose/formant
  use `NoteExpression.setValue(value, Unit)` or `setEvents(AutomationEvents, Unit)`.

## Known issues and comparison pitfalls

- **Resolved: nonzero-start automation.** Bitwig inserts an implicit beat-zero
  anchor. The writer now preserves it, offsets caller points, and validates their
  times before shaping. The live and save/reopen gates passed. Hold curvature
  is explicitly zero, matching native persistence; linear curvature is retained.
  Keep this regression in future gates.
- **Startup race:** TCP handshake can arrive before the Bitwig event queue is
  initialized. `document.list` can briefly fail with `dpz.GKQ() == null`.
  The manual probes retry reads until the requested file is present. Harden
  readiness handling rather than treating connection alone as document readiness.
- **Playback changes snapshots:** after auditioning, live automated parameter
  values and device on/off states can differ from stored base values on reopen.
  In the variation, 30 such fields differed while all clip/note/automation data
  matched, and the new filter sweep was verified by playing the reopened file.
  Do not call these automatically data loss, and do not silently discard them
  in comparisons. Compare stored automation plus values at a controlled playback
  state, or explicitly report the dynamic fields.
- Native beat precision can differ by less than 1e-6; use documented tolerance.
- Read/write repository text explicitly as UTF-8. Windows Python defaults to
  cp1252 and can fail or corrupt Markdown containing typographic punctuation.
- Duplicate track names exist (e.g. Atmos/Pad, Transitions/Phase Plant). Path
  selection is first-match; a complete editing API needs unambiguous addressing.
- Standard DAWproject import is lossy for generic native devices, automation
  and some audio/loop properties. Do not use it as a full reconstruction shortcut.
- `scratch/` contains a full Bitwig installation: never recursively enumerate
  all of it unfiltered. `rg` is unavailable in the current shell; use bounded
  PowerShell/Python searches. Broad decompilation-tree searches have been slow.

## Build, test, and live setup

From the repository root (PowerShell):

```powershell
python -m pytest --basetemp=scratch/pytest-editing-01 -o cache_dir=scratch/pytest-cache-editing-01
python java_bridge/smoke.py
node --check src/openwig/controller/openwig_bridge.control.js
git diff --check
python java_bridge/build.py --bitwig-root scratch/bitwig-6.1.5-live --output scratch/bitwig-6.1.5-openwig.jar
```

Use a fresh `--basetemp` path each run if cleanup permissions are troublesome.
Default pytest temp/cache locations previously caused sandbox permission errors;
placing them under ignored `scratch/` passed. Git metadata, GitHub access, local
Bitwig socket/process access and some build operations may need tool escalation.

The ignored `scratch/restart_recreate.ps1 -Project <file>` helper verifies the
launcher executable belongs to `scratch/bitwig-6.1.5-live`, stops only that process
tree without saving, restores/installs the freshly built JAR, and launches the
specified project with a hidden launcher. Read it before use. Await a successful
build before restarting. Never kill processes by name or touch an unidentified
Bitwig process; there is an old unrelated zombie PID in earlier sessions.

Python live access:

```python
from pathlib import Path
from openwig import JavaSong

with JavaSong() as song:
    print(song.projects())
    song.select_project(Path("scratch/probe-project/probe.bwproject"))
    # Only now mutate this known disposable project.
```

Wait for the exact project path and audio readiness as needed. Native bridge:
port 7778; controller bridge: port 7777. Do not change version compatibility pins.

Existing manual harnesses: `tests/live_java_writes.py`,
`tests/live_java_render.py`, `tests/live_java_recreate.py`. Each has its own
scope and setup; some clear the selected project. All owner artifacts stay
under ignored `scratch/` and must never be added to a commit.

## Update this document on each checkpoint

Record what changed, what passed (including live values and persistence), what
failed or remains uncertain, commit/CI status, which project/process is open,
any in-flight commands and uncommitted work, and the next concrete action.
Keep verified capability claims separate from inspected signatures and plans.
