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
4. **Non-native devices are opaque.** PARTLY FIXED: `device.snapshot` and the
   dump report `kind` (`native`/`vst3`/`vst2`/`plugin`) and `active`. Still
   missing: plugin identity (vendor/name/preset), plugin parameter reads,
   device-chain nesting (layers, drum pads, note FX), and many devices read
   back an empty `display_name`.
5. **Clips are count-only.** PARTLY FIXED: `project.dump` returns arranger
   clips with name, color, start, duration, mute, loop info, play offset, and
   note contents (channel, key, start, duration, on/off velocity, muted) or
   audio fades (664 clips / 776 notes read on the released project). Still
   missing: note expressions (gain/pan/transpose/timbre/pressure values are
   write-only in this API), chance/repeats/occurrence, audio clip sample
   reference (file path), warp/stretch data, launcher clips and scenes.
6. **Automation cannot be enumerated.** Read/write exist per named parameter, but
   there is no "which parameters have automation" listing, no plugin-parameter or
   send/mute automation, no clip-level (launcher) automation, no automation of
   macros/remote controls.
7. **Project-level data:** PARTLY FIXED: `project.dump` reads cue markers
   (time/name/color) and the 10 text metadata fields (title, artist, ...).
   Still missing: time signature and its changes, tempo map as data,
   project name/path, sample rate, key/scale hints.
8. **Mixer detail not readable:** send targets and amounts per track, input/output
   routing, sidechain sources, crossfader assignment, solo/arm, channel width,
   monitor mode (the official API has no getter; the model classes may).
9. **Modulators and Poly Grid** are not covered at all (the JS-path modulator API
   was removed because it crashed the engine; the Java path is untested).
10. **No Python reader or recreate path for the Java bridge.** The old
    `src/openwig/read/` and `recreate.py` target the JS controller bridge (port
    7777). Needed: a `openwig.java_read` module that dumps the whole project to
    JSON (versioned schema, stable ids, includes every item above), and an emitter
    that turns that JSON into `JavaSong` calls, flagging what cannot be restored.
11. **Corpus layer (later goal):** a schema for "all tracks" (project metadata,
    arrangement structure, per-track device chains, note statistics, automation
    shapes, tempo, key), plus optional audio features from the project's `.wav`
    masters. Keep raw dump and derived features separate.

## Process notes

- **Work in the test copy, not the real install.** Reinstalling the patched jar
  into `C:\Program Files\Bitwig Studio\bin\bitwig.jar` needs an *administrator*
  PowerShell; the owner wants to avoid that. The real install is currently patched
  (backup: `bitwig.jar.openwig-original-6.1.5` next to it; `install.py restore`
  undoes it, needs admin). Use the ignored copy `scratch\bitwig-6.1.5-live` with
  `install.py install|restore --bitwig-root scratch\bitwig-6.1.5-live --assume-closed`
  (commands in `HANDOFF.md`). Rebuild: `python java_bridge\build.py --output scratch\bitwig-6.1.5-openwig.jar`.
- **Only one patched Bitwig can run at a time** (both listen on port 7778). Close
  the real Bitwig before launching the test copy.
- **Never save over the owner's projects.** Opening is read-only until saved; the
  reader must make no edits. `project.clear` and `JavaSong.clear()` wipe all
  tracks: use only on a disposable project (`scratch/probe-project/probe.bwproject`).
- Obfuscated names come from CFR 0.152 (`scratch/tools/cfr.jar`). Decompiling one
  class per CFR call is very slow (each loads the whole jar); extract classes
  with Python `zipfile` and use `javap -p -c` for quick inspection. Add exact
  signatures as stubs under `java_bridge/stubs/`, and only list an operation as
  supported in `java_bridge/README.md` after a live test.
- Document edits and reads run on Bitwig's event queue; Bitwig updates some caches
  only after the event commits (see automation notes in `HANDOFF.md`).

## Open questions and housekeeping

- **Unexplained pulse/noise** heard while a patched Bitwig was running, on no
  track, gone when Bitwig was closed, silent in a 12 s loopback capture of the
  default output. Nothing in this branch generates sound. Untested: restore the
  original jar and see whether it persists (needs admin on the real install, or
  just run the unpatched test copy).
- `bridge.capabilities` reports `song_edit: False`; check what that flag means.
- Root `README.md` and `AGENTS.md` do not yet mention Java automation, transport
  and render; update after the reader exists.
- `HANDOFF.md` is untracked on purpose. No PR is open yet for
  `codex/java-bridge-615` (public repo: use a PR, never push `master`).
- Linux support for the Java path is untested; the render capture backend is shared.
