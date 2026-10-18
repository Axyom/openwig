# Java bridge: reader parity and remaining gaps

The owner-approved scope is `READER_PARITY_DESIGN.md`: read the open project
natively at DAWproject depth, with an atomic dump and targeted polling reads.
Read `AGENTS.md` and `java_bridge/README.md` before changing the exact-build
bridge. Work on `codex/java-bridge-615` or a feature branch, never push master.

All live reader checks below used the owner's released
`Axyom - The Substance EXPERIMENT.bwproject` in the isolated Bitwig 6.1.5 copy
on 2026-10-18. The project was not saved. Raw artifacts stay in ignored
`scratch/`; do not commit released-project XML or JSON.

## Verified native coverage

The corrected full dump contains 107 tracks, including 17 groups, 400 devices
in track-level chains, 2831 arranger clips, 2527 notes, and 10 cue markers.
The old 664-clip/776-note figures described an incomplete read and are obsolete.
DAWproject XML counts include nested clip content and launcher instances;
compare equivalent structures through the parity harness.

1. **Converter and parity harness:** `openwig.java_read.export_to_dict(song)`
   exports and parses the archive; `parity_report(dump, exported)` reports
   comparable counts, field coverage, unavailable data, and missing native
   fields. XML `child_order` preserves interleaved device tags. Synthetic
   fixtures and unit checks cover parsing/report logic. DAWproject remains
   the archival baseline. Live synthetic capture via
   `tests/live_java_reader_fixture.py --capture` passed on
   `scratch/probe-project/probe.bwproject`: only three newly authored tracks and
   a sanitized master were retained, yielding four tracks, two notes, and FM-4.
   No existing project was cleared or saved, and no owner XML enters the fixture.
2. **Track paths and polling:** `track.read`, `clip.read`, `device.read`, and
   path-addressed `automation.enumerate` work for group children and master.
   `JavaSong.track_at(path)` exposes `.read()`, `.read_clip(index)`,
   `.read_device(index)`, and `.enumerate_automation()`. Live polling covered
   105 unique track paths, 84 clip reads, 82 device reads, and 96 enumerated
   automation lanes. Duplicate siblings select the first: `Atmos/Pad` and
   `Transitions/Phase Plant` account for the two duplicate paths among 107
   tracks. Literal `/` in titles uses longest-match resolution, verified with
   `Leads/Vocals`. Existing integer addressing remains available.
3. **Scenes and launcher reads:** native scene count 10, launcher slot count
   1070. Slot clips and stop-button flags are readable. Launcher writing is
   excluded by the owner's scope.
4. **Device identity, parameters, and plugin state:** native/plugin identity,
   roles, vendor, preset fields, and saved plugin `.vstpreset` state references
   (`state`, `state_file`, `state_path`, `state_bytes` via `brS.qP3():File`,
   resolving all 121/121 plugins across the project, totaling 357056 bytes) are
   read from the exact-build model. Nested reads (`YhC` single-slot chains,
   `AYz` layers, `EhV`/`MU2` multiband/splitter bands, and plugin `Ce`/`vCL`
   FX/multi-out chains) expanded 400 track-level devices to 978 recursive
   devices across 420 single-slot `chain`s, 67 `layer` chains, and 13 `band`
   chains (including one plugin with a nested FX chain device on
   `master#dev[5]/band:0[2]`), with no truncation or cycle references.
   Recursive reads returned 22726+ native/plugin parameters, including 8393
   plugin parameters.
   Plugin caches depend on resident engine descriptors: initially all 77
   top-level VST3 caches were empty; after settling, 62 were available and 15
   remained `unavailable_model_cache`, yielding 4182 top-level plugin parameters.
   Cache unavailability is explicit and must not be presented as zero parameters.
5. **Routing:** native audio/note input sources and destinations and plugin
   sidechain source reads passed. Six non-null plugin sidechain sources resolved
   to track paths and tap points. Reading model selectors avoids engine graph
   traversal; sidechain writing remains a separate gap.
   A separate disposable-project check verified an effect-track path read
   against the dump and send amount 0.3, mode `AUTO`, effective mode `POST`,
   matching the XML post-fader send type. The final native sidechain gate read
   143 `WYJ` components across recursive devices, including five non-null
   sources; this is separate from the six non-null plugin sidechain sources.
   Before/after exports remained equal and structure/value checks passed.
6. **Notes:** base note fields, chance, repeats, occurrence, recurrence, and
   expression data passed live checks. There were 154 expression envelopes
   containing 316 points.
7. **Audio:** native reads covered 2898 audio events, 5697 warp markers, and
   1425 expression envelopes containing 1529 points, with sample references and
   clip/event settings.
8. **Project/mixer:** metadata text, cue markers, tempo, groove/shuffle,
   current time signature, track activity, solo (`roo.fcV().oLP():boolean`),
   track/scene comments (`roo.lzE():String`, `eNm.Vc():String`), crossfade, and
   sends with target names and modes are native. Track groups and effect/master
   reads share the dump builders.
9. **Composite arranger automation:** per-track dump data includes the full
   native lane/target/unit/point inventory. The live gate found 105 lanes and
   4124 points. All 23 exported lanes and their 568 points matched, including
   values. The other 82 native lanes exceed exporter coverage and are
   diagnostics rather than missing-native gaps. Path-addressed
   `automation.enumerate` returns targets, units, and integer point counts for
   the complete raw inventory; full point arrays are in the per-track dump.
   The legacy index form retains its previous mixer/native-double
   enumeration behavior.
10. **Modulators (inventory, parameters, and bindings):** the gated scratch-copy
    live check returned 904 modulators across 978 recursive devices with zero
    `read_error` and `modulators_status: "native"`. All 904 modulators exposed
    stored parameters (1811 total: 962 `double`, 693 `boolean`, 156 `enum`),
    and 316 modulators exposed 560 modulation bindings (299 `SOURCE`, 232
    `MATH`, 25 `LFO`, 1 `VELOCITY`, 1 `EG`, 1 `KEYTRACK`, 1 `RANDOM`). All
    560/560 bindings resolved a non-empty `target_parameter` and 560/560
    resolved to an exact `{"path", "device"}` (or nested `{"path", "chain",
    "device"}`) `target_owner` via atom identity, migrated `dY3.TEI()`/`dY3.hMI()`,
    `Ovr.U3o()`, and bounded `kX1.L_()` parent traversal (`dY3.Lg()` is a legacy
    field and returns null on 6.1.5 documents). Repeated full dumps were
    identical and retained the same engine process (PID 56272) without a crash
    or hang, targeted `track.read` and `device.read` calls matched the dump,
    and before/after `.dawproject` XML entries and structure/value checks passed.

Value-level XML comparisons passed for 105 first-match track paths, 373 device
identities, 2821 clips, 2360 notes, all 2898 audio events and 5697 warp markers,
and all 154 note/1425 audio expression envelopes and their 316/1529 points.
Duplicate track paths limit the targeted clip/note comparison subset.
Exports taken before and after the verified read cycles were unchanged.
This checks document preservation for those reads, not untested reader paths.
Final checks passed: 94 unit tests, Java bridge smoke, and controller JavaScript
syntax. Exact-build reader signatures and evidence are listed in
[`java_bridge/READER_SIGNATURES.md`](java_bridge/READER_SIGNATURES.md).
The remaining gaps below prevent a claim of complete native schema parity.

## Remaining parity gates

1. **Grid internals and non-`m1C` binding targets — boundary.** Modulator
   inventory, stored parameters (`Ob`/`KOL`/`mqD`), and `Hx3`/`AvA` modulation
   bindings are verified live. Poly Grid / FX Grid patches are read only as
   stored value atoms (module/cable graph topology is not read), and modulation
   targets backed by plugin `GAN` descriptors rather than `m1C` atoms resolve
   to track-level ownership (`QFL.xTe()`) if `dY3.hMI()` does not reference an
   indexed `Wcl` device. Any further investigation retains the scratch-only,
   read-only getter constraint and ends on a crash, hang, or engine restart.
2. **Plugin parameter caches — partial.** Fifteen top-level VST3 caches remained
   unavailable in the final settled check. No engine calls or guessed values
   should fill them. Availability is reported for each device/read.
3. **Runtime breadth — partial.** Layer chains passed; drum pads and single-chain
   containers still need a project that exercises those representations. The
   released project and generated fixture had empty launcher slots, so filled
   launcher content and additional plugin formats need separate live samples.
4. **Other fields:** project file path/sample rate, distinct solo/arm flags,
   time-signature change depth, and any fields still marked partial/export-only
   by the final parity report remain unresolved unless individually verified.
   No new variable-tempo work is authorized under this design.
5. **Remaining schema checklist:** track comments and audio-channel counts;
   plugin version/state references; optional clip content-time/fade-time units,
   play-stop/reference fields; and optional metadata composer/arranger/website
   fields. Empty names/metadata and fields represented at a different JSON
   level also appear in the strict coverage checklist. Its flat missing-field
   list is not evidence that every item contains unread data in this project.
   The final scoped baseline had no missing structures, no missing exported
   arranger lanes, and no mismatches in the values checked by
   `tests/java_reader_checks.py`; `native_parity` remains false honestly.

Each implementation item needs signature verification against the original
JAR, a build, smoke/unit checks, its own live read cycle, a shrinking parity
diff, and documentation of the observed result. Keep pending claims separate
from verified claims.

## Separate scope

Composition and real-time loopback rendering already passed disposable-project
live checks: track creation/deletion, effect/master devices, native parameters,
MIDI arranger clips, automation write/read, transport, and audible WAV render.
This does not establish full `Song` API migration. Native enum writes and the
remaining write/property routes retain their existing verification limits.

Corpus collection, batch project opening, derived features, recreate, and
DAWproject import are design phase 4. They require a separate design and are
outside the current work. Never save over the owner's projects, and use clear
operations only on disposable projects.
