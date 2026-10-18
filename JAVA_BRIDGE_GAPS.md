# Java bridge: gaps to fix (handoff to the next model)

Goal from the owner: **read a real, finished project entirely** (starting with
`C:\Users\Axyom\Documents\Bitwig Studio\Projects\Finished\2025\Axyom - The Substance\Axyom - The Substance EXPERIMENT.bwproject`,
a released track, opened for experiments), then collect the data of all their
tracks so a model can later help produce plausible new ones. Not in scope: launcher
clip *writing*, variable tempo polish. Reading launcher clips is wanted for the corpus.

Read `AGENTS.md`, `java_bridge/README.md` and `HANDOFF.md` first. Branch:
`codex/java-bridge-615` (last pushed commit when this was written: see `git log`).

## Verified state (what works today)

Composition through `JavaSong` works end to end: tracks (instrument/audio/hybrid),
effect tracks, native device insert, native device parameters, MIDI arranger clips,
automation write/read (volume, pan, native device params, tempo), master factory
devices, transport, real-time WAV render. A 3-track, 8-bar song was built and
rendered audibly in the test copy. The default WASAPI output must not be muted,
otherwise renders are silent (`render` now warns).

## Gaps found on the real project (all reproducible)

Probe on the owner's open project: `track.count` = 7, `fxtrack.count` = 0,
`master.device_count` = 11, tempo 150, loop region 792 beats.

1. **Group tracks crash `track.snapshot`.** FIXED: `track()` accepts
   `TrackOrTrackGroup` (groups convert via `asTrack()`), and `project.dump`
   returns the full nested group tree with children and track kinds.
   Live-verified on the released project (7 top groups, 4 levels deep).
2. **Master chain is write-only by count.** PARTLY FIXED: `device.*` calls now
   accept track index -1 for the master and negative indexes for effect
   tracks; `project.dump` lists master devices with kinds and enabled state.
   Still missing: master device parameters by index.
3. **Effect tracks are only create/delete/count.** PARTLY FIXED:
   `project.dump` includes effect tracks as full track dumps. Still no
   per-effect-track snapshot/rename/update routes.
4. **Non-native devices are opaque.** PARTLY FIXED: kinds + `active` in dump;
   device identity (id/name/role, including plugins) and full parameter
   values are readable through `project.export_dawproject` XML instead.
   Still missing in the JSON dump: plugin identity, plugin parameters,
   chain nesting, non-empty display names.
5. **Clips are count-only.** PARTLY FIXED: arranger clips + notes via dump;
   audio sample references, warp markers, launcher clip slots and scenes
   are readable through `project.export_dawproject` XML. Note expressions
   remain write-only in the document API (export covers them where set).
6. **Automation enumeration:** PARTLY FIXED: `automation.enumerate` lists
   mixer + native-device lanes with point counts for one top-level track
   or the master. Still missing: VST parameter lanes, send/mute lanes on
   child tracks (children are not index-addressable), clip-level (launcher)
   automation. DAWproject export includes all arrangement automation points.
7. **Project-level data:** MOSTLY FIXED: cue markers, metadata text, tempo,
   groove/shuffle, and the current time signature are in `project.dump`;
   time-signature *changes* remain write-only in the API (the DAWproject
   export carries the signature). Project file path and sample rate are
   still not exposed.
8. **Mixer detail:** PARTLY FIXED: sends with target names, crossfade mode,
   and channel `is_active` are in the dump; routing destinations and solo
   are in the DAWproject export. Still missing in JSON: input routing,
   sidechain sources, solo/arm as distinct flags.
9. **Modulators and Poly Grid** are not covered at all (the JS-path modulator API
   was removed because it crashed the engine; the Java path is untested).
10. **No Python reader or recreate path for the Java bridge.** PARTLY FIXED:
   `JavaSong.dump()` (JSON) and `JavaSong.export_dawproject(path)` cover
   reading; both were live-verified on a released project. Still missing:
   a `java_read` corpus module over them and an emitter that rebuilds a
   project from dump/export (`ApiProject.importDawProject` exists for a
   future write path).

11. **Corpus layer (later goal):** the DAWproject export is the backbone
    (open XML, full fidelity, Bitwig's own serializer); `dump()` gives the
    convenient JSON view. Keep raw exports and derived features separate.

## Verified live (2026-10-18, released project)

- `project.dump`: full tree, 664 clips, 776 notes, 10 markers, transport
  (150 BPM, 4/4, shuffle 0.435), master 9 native + 2 VST3.
- `project.export_dawproject`: 139 KB structure-only archive; project.xml
  holds 107 tracks, 5729 clips, 2527 notes, 10 scenes, 5697 warps, device
  ids/names/roles, routing, automation points.
- `automation.enumerate`: master lane device 6 `AMPLITUDE`, 13 points.
