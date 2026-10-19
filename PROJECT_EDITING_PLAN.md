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
   - Implemented: native independent clip copy (same/cross track), split, MIDI
     content replacement/clear, gain/pan/pitch/timbre/pressure linear expressions.
   - Next: individual event properties, expression shapes and remaining probability
     controls; continue arbitrary audio-event editing.
   - Verify clips crossing edit boundaries, nested tracks, effect/master
     automation, markers, invalid input, and save/reopen persistence.
2. **Audio creation and editing — pending.**
   - Implemented: local RAW sample import, source offsets, container/event fades,
     event import/move/resize/gain/mute/reverse/exact deletion and empty-lane reuse.
   - Implemented state authoring: warp modes/markers/content offset, pitch,
     formant/pan and processing settings; save/reopen and reopened pitch proof.
   - Done: immediate stretch/REPITCH/pitch regression traced to orphaned deleted
     tracks; main/FX deletion now removes visible and owning memberships.
   - Done: warped-event reverse, forward/reversed source-second offsets, and
     formant envelope resolution (8..512), including nonlinear mapping and DSP.
   - Pending: general expression curves and the remaining note controls.
   - Use authored synthetic audio fixtures. Verify timing and audible output,
     media collection, and save/reopen; never use track counts as sole proof.
3. **Plugins, nested device chains, and routing — pending.**
   - Discover installed plugin identities; insert supported formats; load/save
     state; read/write parameters and automation; manipulate nested chains.
   - Complete routing and modulation edits, documenting actual format coverage.
     Resident plugin caches may be unavailable; never invent values.
4. **Launcher clip slots done; scene-bank editing pending.**
   - Scene name/comment/color edits landed earlier (`update_scene`).
   - Launcher slots landed 2026-10-19: author MIDI/audio slots, arranger-to-slot
     copy, container edits, shared audio-event edits via slot addressing,
     clear/delete with stop-button control, launch settings, launch/stop/
     return-to-arranger playback, occupied-slot/enum/range rejection, and
     save/reopen persistence with audible relaunch. Gate:
     `tests/live_java_launcher.py`; evidence `scratch/editing-launcher-gate-2/`.
   - Slot creation stays bounded by the project scene bank (out-of-range
     indexes are rejected); the reader already guards `clipSlot` growth.
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

Active scope confirmed by the owner: finish audio/MIDI details; plugin insertion,
state/parameters, nested chains and routing/modulation; editable JSON project
reconstruction; complete-project audible and persistence verification; and Grid
module/cable/parameter editing. Keep this file current and commit/push each
verified step. Scene-bank structure remains part of the overall project goal.
Current work begins with warped audio reverse/source offsets and expression/
note-property authoring, then proceeds through the remaining areas. This is
in-progress work; the checkpoints below record completed evidence.

### Latest verified step: warped audio details

Implemented warped reverse and absolute source-second offsets through forward
and reversed warp maps, validated formant envelope resolution (8..512), and
corrected warped source-stop readback. Native reverse mirrors both markers and
expressions; reverse twice restores the original snapshot. Full gate:
`tests/live_java_audio_details.py`; evidence `scratch/editing-details/gate-1/`.
Nonlinear three-tone audio at 150 BPM verifies forward, reverse, forward offset,
and reverse offset; rejected edits are atomic, source copies independent, and
launcher content addressing works. Save/reopen: zero reader differences and
1320/880/440 Hz shifted-window order. **208 unit tests passed**.

Runtime: the disposable host currently has
`scratch/editing-details/gate-1/saved/project.bwproject` open and stopped, with the
new patched JAR installed. Preserve this evidence; use the separate
`scratch/editing-details/work/project.bwproject` for the next authoring step.
Resolve current process IDs again; do not use old PIDs from transcripts. Next:
expression curves (including auditing RAW expression time units), individual
note edits and probability/repeat controls, followed by plugins/routing, Grid,
editable JSON reconstruction and complete-project verification.

### Last completed increment: track ownership fix


2026-10-19: the apparent immediate warp/DSP regression was traced to **track
ownership**, not a missing processing-cache notification. `deleteTrack` removed
only the visible reference (`Yko.jPB(Kkl)`), leaving the actual owned track and
its audio engine alive. After clearing the fixture, the reader reported zero
tracks but engine-only loopback still played the previous 660/1316 Hz material.
Repeated fixtures accumulated audible hidden tracks. Save/reopen discarded the
unreferenced tracks, explaining the earlier misleading cache diagnosis.

Fix: pair main reference removal with `Yko.jPB(Kkl,true)`; pair FX reference
removal `Yko.Gcr(roo)` with owning removal `Yko.IYM(roo,true)`. Both pairs match
the native `n1z` Delete Tracks membership sequence and remain inside undo.
Postconditions check visible counts and the target's cleared parent. Fresh
exact-build CFR sources: `scratch/decomp/warp-sync/`, especially packaged
`HYn`, `Yko`, `oZU`, `ZfA`, `n1z` and `ymV`. Class-name collisions are common;
resolve imports rather than trusting unqualified decompiles.

The permanent `tests/live_java_warps.py` gate now activates audio, supports
`--repeat`, checks immediate stretch/REPITCH/+7 playback, and checks that old
fixture tones are absent after clear. It covers nested groups and FX deletion.
Optional Windows process loopback (`tests/windows_process_loopback.cpp`,
`tests/live_audio_capture.py`, `--process-capture ... --engine-pid ...`) excludes
other applications, which were contaminating system recordings during this
investigation. Use the **audio-engine PID** after activation, not a launcher PID.
Metronome clicks can remain: the clear check rejects fixture tones rather than
requiring endpoint-wide silence.

Evidence: a negative control built from committed `3ffa002` fails the new gate
at `scratch/warp-negative-control/cleared.json`: zero visible main/FX tracks,
fixture-tone RMS **0.0337323** against a **0.001** limit. Corrected repeated gates
`scratch/warp-fixed-gate/run-{1,2,3}/` and final nested/FX gate
`scratch/warp-fixed-final-gate/run-{1,2,3}/` all pass. Each run saves its own
native project, records immediate renders, and writes main/group and FX deletion
reports (remaining tone RMS about **5.1e-7**). The third saved fixture reopened
with **zero reader differences**, three audio clips/events, and 660/1316 Hz
shifted playback. Unit suite: **201 passed** (including capture calibration);
Java build/JAR protocol smoke, checked-in C++ capture-helper build, controller
syntax and whitespace checks passed. Consult the feature-branch commit/CI for
remote status; no native engine binary was modified.

Runtime is clean: the identified disposable host was closed without saving and
`scratch/bitwig-6.1.5-live/bin/bitwig.jar` restored to the exact original hash.
The tested patch is retained as `scratch/bitwig-6.1.5-openwig.jar` and
`scratch/warp-fixed-verified.jar`. No bridge listens on 7778. The preserved
reopened fixture is `scratch/warp-fixed-final-gate/run-3/saved/project.bwproject`;
copy it to a separate work directory before future destructive authoring.
The owner's original Substance project still hashes to
`3cef520796171bc0ac4088fc89c142ae04f04711215c3bb77b648a23fa5b17a8`.
The old unrelated PID 20784 zombie remains outside this test lifecycle.

No native engine was disassembled. Unsuccessful scope/copy-order experiments
were removed; the actual warp setters are unchanged. Only the diagnosed/tested
regression is closed; do not infer audible certification of all ten algorithms
or mark the overall goal complete. Next work remains warped audio reverse/
curves, plugin and nested routing writes, scene-bank structure, Grid, and JSON
reconstruction.

### Previous launcher increment

Latest increment (2026-10-19): launcher clip slots are live-verified end to
end. `JavaTrack` now exposes `read_launcher_slot`, `put_launcher_notes`,
`import_launcher_audio`, `copy_arranger_to_launcher`, `clear_launcher_slot`,
`update_launcher_clip`, `delete_launcher_clip`, `update_launch_settings`,
`launch_slot`, `stop_launcher`, and `return_to_arranger`; the audio-event
methods accept `slot=` addressing for shared content edits on slot clips.
Java side: `launcher.*` methods plus slot-aware `clip.*`/`audio_event.*` in
`OpenwigDocument`, routed through `Bootstrap`. Clearing uses the native
nullable slot setter (`yk.jPB(XEQ)`) because the public `ClipSlot.setClip(null)`
wrapper throws. Launch is the same `fmH.jPB(boolean, boolean)` entry Bitwig's
controller API uses; launch settings read/write through `OkK`
(`sGy`/`yi1`/`CaN`/`ncg` and `IYM(RXG)`/`jPB(xSr)`/`IYM(bP)`/`p3P(double)`),
verified against fresh exact-build CFR sources in `scratch/decomp/editing/`.

**Headless audio-engine requirement:** a freshly launched hidden Bitwig can
start with its audio engine disconnected; slots then silently refuse to launch
(`zE1.IYM` no-ops unless the track is engine-ready) and transport cannot play.
`document.audio_status`/`document.activate_audio` connect it. The gate
activates before any launch; future playback tests should too.

Final gate: `scratch/editing-launcher-gate-2/`. The 120 BPM gate authored a
MIDI slot with full launch settings, an audio slot from a synthetic 440 Hz
tone, and an arranger copy; verified occupied-slot rejection and atomicity,
arranger-source independence, shared audio-event edit via slot addressing
(-6 dB gain expression), container resize, clear/delete with stop-button
control, out-of-range slot rejection, launch -> playing, stop, and
return-to-arranger states, plus an audible 440 Hz render while the slot
loops. Save/reopen: **zero reader differences** (`persistence.json`), all
launch settings and slot content intact, and the reopened slot launched and
rendered the tone again (`reopened-launch.wav`, 440.0 Hz). No owner material
was used. `scratch/editing-launcher-gate/` holds the earlier ad-hoc evidence.

195 unit tests (launcher wire contract plus rejection coverage added),
exact-build Java build, JAR/protocol smoke, controller syntax and whitespace
checks passed. CI status for this increment's commit to recheck after push.

Still open: warped audio reverse/curves, plugins/nested routing (milestone 3),
Grid (5) and documented JSON reconstruction (6). The immediate-DSP regression
was resolved in the current checkpoint above. Scene-bank editing (add/remove/reorder scenes) was not
part of this increment; only scene metadata edits exist.

At that launcher handoff the host was closed and its JAR restored. Its separate
`scratch/editing-launcher-gate-2/work/project.bwproject` is the disposable host
used by the current warp investigation. Never use the saved launcher evidence
as the clear target. One old dead 24 K `BitwigStudioApp.exe` zombie (PID 20784)
holds no ports/files; do not act on stale PIDs. Re-identify processes first.

### Previous warp increment

`update_audio_event` authors warp modes, explicit beat-to-
source-second markers, warped content offsets, transpose/formant/pan constants,
formant preservation, onset preview, transient mode/rate, grain size and tail
mode. All ten mode names were read back live. Markers and settings are staged
and validated before mutation; invalid ranges, enums, EOF mappings, RAW pitch
and unsupported combinations fail explicitly. RAW-to-warp requires markers.
Source-offset/reverse still require RAW; reversed material must first be made
forward before replacing its warp mapping. General curves and envelope
resolution remain pending.

**Historical limitation, now explained:** the original increment verified state
and reopened DSP but recorded apparent stale immediate processing. The current
checkpoint establishes that retained owned tracks from incomplete deletion were
mixing old audio into later fixtures. `--live-render` is now an ordinary passing
regression with repeated author/clear coverage; it no longer denotes a known
failure. `--verify --render` still checks native persistence separately.

Final gate: `scratch/editing-warp-final-gate/`. At 150 BPM, markers
[[0,0],[5,1],[7.5,3]] stretch the first source second over two arrangement seconds
and the remaining two source seconds over one. Probes observed pitch-preserving
440/880 Hz, REPITCH 220/1760 Hz, and seven-semitone shift approximately 660/1316 Hz
(expected 659.255/1318.510; FFT tolerance 8 Hz). Final reopened fixture includes
an independent additional clip carrying nondefault processing settings and has
**zero reader differences** across three audio clips/events, with main shifted
playback again 660/1316 Hz. See persistence.json and reopened-pitch.json/WAV.
No owner material was used.

179 unit tests, exact-build Java build/JAR protocol smoke, controller syntax and
whitespace checks passed. Committed/pushed as **`6a3c2b3`** on
`codex/java-bridge-615`; its CI run plus `4711a5e`/`a3ed1b0` were green.

**Investigation evidence for the next agent:** failed immediate-playback WAVs
and JSON reports remain in `scratch/editing-warp-public-gate{,-2,-3,-4}/`.
Saving/reloading exactly a failing snapshot in
`scratch/editing-warp-copy-diagnostic/` preserved all reader data and corrected
its playback. Removing the muted source or setting its volume to zero did not
fix immediate playback. Native `KoO.JTi()` (source-cache refresh), `fv_()` (content
invalidation), and replacing events with fresh native identities did not make
all transitions reliable. These unsuccessful code workarounds were removed;
do not claim they fixed it. Fresh exact-build CFR sources include
ApiModificationProcessor, KoO/pUn/Tc, BBa/BOt, MjL and yMP under
`scratch/decomp/editing/`. `KoO.JTi -> BBa.QmZ -> yMP.kxC -> e2n.tY1` refreshes
source state; `KoO.fv_` calls `MjL.UqY/IxG`. The old cache-invalidation hypothesis was superseded by the owning-bank
deletion diagnosis in the current checkpoint.

The warp gate's evidence and saved fixture remain in
`scratch/editing-warp-final-gate/`, preserved. Next: continue with the remaining
audio edits, plugins/nested routing, Grid and JSON reconstruction. The overall goal remains active
and incomplete.

### Previous RAW audio-event increment

Latest increment: public `JavaTrack.import_audio_event`, `update_audio_event`,
and `delete_audio_event`, plus an important copied-audio playback correction.
Edits cover local start/duration, source offset in seconds, gain in decibels,
mute, event fades, RAW reverse, and exact event deletion. Imports support multiple
nonoverlapping events in an existing audio clip, including an emptied lane.
Edits make content independent before mutation, preserve other clips and the
container, maintain event sorting, and reject invalid/overlapping requests
before touching content. Gain writes intentionally replace the gain curve with
a constant. Offsets/reverse currently require RAW; set offsets while forward,
then reverse separately. Warp/pitch and warped-event reverse remain pending.

Manual gate: `tests/live_java_audio_events.py --disposable --project <open>
--output <fresh scratch directory> --render [--tempo 150]`. It authors a source
with 440 Hz in the first second and 880 Hz in the second. Native reverse produced
880 then 440, forward produced 440 then 880. Verified at 120 and 150 BPM. The final
150 BPM fixture reopened with **zero differences**, with audible reversed order
880/440 Hz. A separate trimmed-window probe at 150 BPM preserved offsets
1.05 -> .25 seconds when reversing a two-beat event starting at source .25 s
(`partial-reverse.json`). Evidence: `scratch/editing-audio-final-gate-150/`, persistence and
forward/reverse/reopened-reverse JSON+WAV reports. The test includes nested
tracks, source-copy independence, atomic rejected edits, source EOF rejection,
exact deletion and deleting/re-importing the last event. Gain/fades/mute/offset
readbacks are asserted separately. Optional recording waits one second for
engine updates; it is system loopback, so unrelated audio can invalidate it.

**Copied audio correction:** first-playback testing at 150 BPM exposed that
`b8237d7`'s copies could read/save correctly yet play inconsistently. Native
`xh3.IYM(XCv,dP)` insertion finalization was missing after attach/make-unique.
It is now invoked with the destination timeline and editor context. Because
that callback initializes automatic fade-in defaults, the source's explicit
fade-in time/gain/curve are restored afterward. Fresh 150 BPM copied playback
now passes. Copy/split/MIDI/fade regression gate and save/reopen also pass:
`scratch/editing-content-finalization-gate-2/persistence.json`, zero differences.
This is why metadata-only checks cannot establish complete playback parity.

161 unit tests passed; exact-build build, Java protocol/JAR smoke, controller
syntax and whitespace checks passed. Prior `b8237d7` and `2d14f7a` CI are green.
This increment is committed/pushed as **`4711a5e`** on `codex/java-bridge-615`.
The first post-push CI listing did not yet show its run; recheck GitHub Actions
for this SHA. Implementation working tree is clean. No live sessions remain.

Current scratch project:
`scratch/editing-audio-final-gate-150/saved/project.bwproject`, stopped.
Last observed scratch launcher PID 41644; always re-identify before process work.
All owner originals and the saved musical variation are preserved.
Next: native warp/pitch and audio processing settings/expressions, including
isolated audible proof; then plugins/nested routing, launcher, Grid and JSON
reconstruction. The full goal remains active and incomplete.

### Previous native content increment

Latest increment: native `JavaTrack.copy_clip`, `split_clip`, and
`replace_notes`. Live gate `tests/live_java_content.py` covered nested tracks,
same-track and cross-track independent copies, MIDI/audio splits, native
loop/play offset/fade preservation, content replacement/clearing, atomic reject
of malformed later notes, five linear expression types, and zero reader-visible
differences after save/reopen. Data: `scratch/editing-content-gate/`, including
`before.json` and `persistence.json`. Source fixtures are synthetic only.

Public note replacement accepts dictionaries with key/duration and optional
channel/start/on_velocity/off_velocity/muted/chance/expressions. Expressions map
gain/pan/transpose/timbre/pressure to explicit Bitwig units and local beat/value
points. Replacement intentionally replaces all note content, unlinks shared
native content first, and preserves the clip container. It does not yet expose
every recurrence/repeat/occurrence control or arbitrary interpolation shapes.
Native splitting retains the complete content, shifts the right piece's play
offset, and clears interior audio fades; no duplicate undo wrapper is used.

146 unit tests passed, exact-build build and Java protocol/JAR smoke passed,
controller syntax and whitespace checks passed. Prior commits `4f78e38` and
`24aab45` both have green CI. This increment is committed/pushed as **`b8237d7`**
on `codex/java-bridge-615`; its CI and the `2d14f7a` handoff CI are green.
The implementation working tree is clean. No in-flight live sessions remain.

Previous scratch project: `scratch/editing-content-gate/saved/project.bwproject`,
stopped. Last observed scratch launcher PID 61864; always re-identify before
process actions. The previous clip fixture and owner deliverable are preserved.
Next: audio-event offset/gain/mute/reverse/warp operations, isolated pitch proof,
then plugin/nested routing, launcher, Grid, and JSON reconstruction milestones.
The full goal remains active and incomplete.

### Previous clip editing increment

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
isolation. Committed and pushed as **`4f78e38`** on `codex/java-bridge-615`.
GitHub CI run `39176057597` completed successfully:
https://github.com/Axyom/openwig/actions/runs/39176057597 .
The working tree was clean after the implementation commit.

Previous scratch project: `scratch/editing-clip-gate-5/saved/project.bwproject`,
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
- Fresh exact-build `KoO` is now decompiled in `scratch/decomp/editing/`.
  `KoO.eUA(boolean)` sets reverse directly on `jsk().AOA(boolean)`, but the
  native reverse command `KoO.IYM(fKr)` also reverses expressions via its
  superclass and swaps RAW start/stop source offsets (`IT3()`, `IYM(dP)`,
  `UJq(double)`, `Nwn(double)`), then `JTi()`. Inspect `pUn`/superclasses before
  using it: direct flag toggling alone would miss important playback semantics.
  Do not expose reverse until a synthetic asymmetric-source gate passes.
- Some root `scratch/decomp/` files (e.g. xh3/fKr) are from older symbol builds.
  Prefer the fresh exact-JAR copies under `scratch/decomp/editing/` when names
  disagree (fresh uses cPD/dP/oZU; older files mention cpd_2/dp_2/ozu_0).
- `ApiClip.setTime` ultimately uses native event setters; attached timeline
  sorting and overlap behavior must be tested before exposing clip moves.
- `ApiAudioClip` is not a class in this build. The actual implementation is
  `ApiAudioNoteClipEvent`; its content wrapper is `ApiAudioNoteClipContentTimeline`.
  These and `ApiAudioNoteEvent`, `ApiEnduringEvent`, `NoteExpression` are now
  decompiled under `scratch/decomp/editing/`. Audio event gain/pan/transpose/formant
  use `NoteExpression.setValue(value, Unit)` or `setEvents(AutomationEvents, Unit)`.

## Known issues and comparison pitfalls

- **RAW expression time labels need an audit before JSON reconstruction.**
  A two-second RAW event reversed at 120 BPM stored a gain point at time 4;
  at 150 BPM it was time 5, while the reader labels RAW expression time as
  seconds. This suggests native stored beats need conversion or a corrected
  time-unit label. Constant-value playback does not establish curve timing.
  Check the exact exporter/model time converters and add a time-varying audible
  gate; do not silently reuse these labels in the editable JSON schema.


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
