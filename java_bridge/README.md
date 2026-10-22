# Bitwig 6.1.5 Java bridge experiment

This directory contains the first, independently written replacement for the
controller-script transport. It is **not yet a replacement for openwig's full
Song API**. The current Java endpoint supports `ping`, `host.version`,
`bridge.capabilities`, `document.status`, `transport.get_tempo`,
`transport.set_tempo`, `track.count`, and appending a named instrument track
with `track.create`. Main-track `track.snapshot`, `track.rename`,
`track.set_volume`, `track.set_pan`, and `track.set_mute` are also available.
`device.count` and `device.insert_factory` append native factory devices by
their exact catalog names, such as `FM-4`.
`device.snapshot` returns each device's index, optional display name, and
enabled state; `device.set_enabled` toggles that state.
`device.rename`, `device.set_expanded`, and `device.delete` edit the track's
device chain. `device.list_parameters` lists preset paths and types for a
loaded native device. `device.get_parameters` reads numeric, boolean, and
enum index values; `device.set_parameters` writes them. Numeric double values default to a
normalized 0..1 scale. Pass a Bitwig `Unit` name to read or write in that
unit instead (for example `PERCENTAGE_OF_RANGE` uses 0..100). Enum parameters
use integer indexes, regardless of the unit argument.
`track.create` also appends `audio` and `hybrid` tracks (`type` parameter).
`track.set_color` sets RGB(A) in 0..1 and `track.snapshot` reads the color
back as a hex string; Bitwig snaps track colors to its palette, so the readback
can differ slightly from the requested value. `track.set_monitor` takes
`OFF`, `ON`, or `AUTO` (the document API exposes no readback for it).
`track.set_send` sets one send amount in 0..1; send slots exist per effect
track and appear in `track.snapshot` as a `sends` array.
`track.delete` removes one main track by index. `project.clear_main_tracks`
removes every main track and reports the number removed. `fxtrack.count`,
`fxtrack.create`, `fxtrack.delete`, and `project.clear_effect_tracks` cover the
effect bank. `master.device_count`, `master.insert_factory`, and `master.clear`
cover the master device chain. `project.clear` removes main tracks, effect
tracks, and master devices and reports each count. `project.dump` reads the
whole open project read-only: project metadata, cue markers, and the full
track tree (nested groups with children, track kinds, mixer volume/pan/mute,
sends with target names, device chains with `native`/`vst3`/`vst2`/`plugin`
kinds, and arranger clips). Note clips include every note field
(channel, key, start, duration, on/off velocity, muted); audio clips include
fades. Python: `JavaSong.dump()`.
It also supports `clip.count`, `clip.note_count`, and
`clip.create_arranger_with_notes` for a selected main-track index. It reports
`song_edit: false` because feature parity has
not been reached. The existing Python
`BridgeClient` can talk to it on `127.0.0.1:7778`. The controller remains on
port 7777 until the Java backend reaches feature parity.

The Java transport also implements `transport.snapshot`, `transport.play`,
`transport.stop`, `transport.set_position` (`beats`), `transport.set_loop`
(`on`), and `transport.set_loop_region` (`start`, `length`, in beats).
`transport.beats_to_seconds` converts a beat position using Bitwig's native
tempo map, including arranger tempo automation.
Snapshots contain `playing`, `beats`, `loop`, `loop_start`, and `loop_length`.
These call the same native transport methods that Bitwig's `TransportProxy`
uses, without starting a JavaScript controller.

`automation.write`, `automation.read`, and `automation.clear` manage arranger
lanes for track `volume`, `pan`, native double parameter paths, and `tempo`.
Writes replace the lane with `[beat, value, curvature?, interpolation?]`
points; interpolation is `linear` or `hold`, and curvature is -1..1. Mixer
and native values default to normalized 0..1, with pan 0.5 at center.
Tempo values use BPM. Native parameters also accept an explicit Bitwig Unit.

The patch is locked to the Windows Bitwig Studio 6.1.5 build whose original
`bin/bitwig.jar` has SHA-256
`d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49`.
It modifies only `BitwigStudioMain.main` to call our bootstrap and adds five
classes. The direct adapter makes JVM calls to the exact-build Bitwig
document API. Parameter path discovery uses reflection on the exact-build
native model's read-only child and identifier methods; parameter reads and
writes use Bitwig's document API. Clip insertion starts and closes a
Bitwig undo transaction around the arranger timeline edit. It does not contain
Superpowers code.

## Build and test

Run from the repository root with Python 3.11+ and a JDK 21 with `javac`:

```powershell
python java_bridge\smoke.py
python java_bridge\build.py --output scratch\bitwig-6.1.5-openwig.jar
python -m pytest tests\test_render.py
```

`smoke.py` checks the JAR diff and the framed JSON-RPC protocol. For a live
test, install into a separate copy of Bitwig and launch that copy. The live
test passed on 2026-10-17: the bundled Java 25 runtime loaded the patch;
openwig's Python `BridgeClient` received the handshake, changed tempo from
128 to 123 BPM and read it back, and appended an instrument track to a
disposable copy of `demo_openwig.bwproject` (track count 12 to 13). A later
live check created a one-note arranger clip on a new instrument track and
read back one clip and one note. These are small integration checks, not a
Song API parity test. These checks used a separate test installation.
Track rename, volume, pan, and mute were verified with readback on another
disposable track. Volume and pan use the controller-compatible normalized
`0..1` scale; pan `0.5` is center.
FM-4 insertion was verified on an isolated instrument track with device count
changing from zero to one. Bitwig requires an undo transaction around this
edit; the bridge uses the owning track timeline's transaction token.
The FM-4 device's enabled state was toggled off and on with live readback.
Bitwig returns an empty display name for an unrenamed factory device.
Deletion of one new track and then all 12 main tracks in the disposable
project was verified by count readback. A fresh FM-4 track could be created
after clearing, then deleted. The original project file was not saved.
Effect-track deletion and clearing were verified with one and two newly
created effect tracks. A full clear then removed 12 main tracks, one effect
track, and five master devices from a freshly opened disposable project;
all three counts read back zero, and a new FM-4 track could be created.
Audio and hybrid track creation, track color with hex readback (palette
snapping observed), send amounts with readback against a new effect track,
and monitor mode changes (accepted without readback; the API has no getter)
were verified live on 2026-10-17 with invalid-input rejection checks.
FM-4 parameter discovery, numeric read/write (`OUTPUT` at normalized 0.35,
equivalent to 35 `PERCENTAGE_OF_RANGE`), and boolean read/write
(`OP1_ENABLE`) passed on a disposable track. An out-of-range normalized value
was rejected without changing the parameter. Device rename, expansion, and
deletion also passed. Run `tests/live_java_parameters.py` to repeat the
parameter check. A newly inserted device may expose only a few paths while
it loads; callers should wait until the desired path appears.
Solo writes now use the exact-build model and passed disposable-project
readback checks (see below). Arm writes remain missing.

### Additional write checks (2026-10-18)

`tests/live_java_writes.py --disposable` passed on the isolated 6.1.5 copy
with `scratch/probe-project/probe.bwproject`, without saving the project.
The flag is required because the script clears the active project. Run with
`$env:PYTHONPATH="src"; python tests/live_java_writes.py --disposable`.

Verified operations are project metadata (`JavaSong.set_metadata`), cue-marker
creation (`add_cue_marker`, beats and optional hex/RGB(A) color), scene name,
comment and color (`update_scene`), group creation (`group_track`), and nested
instrument/audio tracks (`track`/`audio_track` with `parent`). Track solo,
comment, activity and audio destination writes are exposed as `.solo()`,
`.comment()`, `.active()` and `.route_to()`. Nested volume/pan and note clips
passed dump readback. Nested native-device enum writes, rename, expansion,
enable/disable and snapshots also passed. Expansion is accepted without a
readback assertion. Device parameter discovery waits for the model to load.

Scene-bank structure is editable through Bitwig's native undoable "Insert
Scene", "Delete Scenes" and "Reorder Scenes" actions: `scenes()` reads rows
without creating them, `insert_scene(index=None)` (None appends),
`delete_scene(index)` and `move_scene(index, destination)` resize or reorder
every track bank, including group children and effect tracks. `update_scene`
now rejects out-of-range indexes instead of growing the bank, and runs inside
one undo transaction. Gate `tests/live_java_scenes.py` (process loopback
required) verified an emptied bank, insert/move/delete with stop-button
preservation, audible relaunch of moved rows, rejected-input atomicity and
native save/reopen with zero reader differences.

Path handles from `track_at()` support these edits. Renaming a root group
updates its handle for subsequent child creation; nested titles containing
`/` retain a usable path after renaming. Existing descendant handles must be
selected again after renaming an ancestor. Duplicate titles still resolve
to the first match.

`JavaSong.import_dawproject(path)` uses Bitwig's native two-phase importer.
The synthetic export/clear/import check recovered groups, nested notes,
metadata, solo and comments. Import appends content to the active project;
`clear()` does not remove scenes or markers (the check grew from 13 to 26
scenes). This is a limited import check, not a lossless round-trip claim.
Embedded audio, plugin restoration, device-state fidelity and arbitrary
third-party archives remain unverified. Corpus/recreate work remains gated.

Arranger automation passed live checks on a disposable FM-4 track: volume and
pan normalized readback, native `OUTPUT` parameter automation, curvature 0.4,
hold interpolation, lane replacement, and invalid-input rejection without
changing the existing lane. Tempo automation also passed point readback.
Repeat using `$env:PYTHONPATH="src"; python tests\live_java_automation.py` on a
disposable project with no existing tempo automation. Clearing a lane removes
its time-varying automation; Bitwig can retain one constant fallback point.

## Native project reader

Read-only live checks on 2026-10-18 used the owner's released
"Axyom - The Substance EXPERIMENT" project in the isolated Bitwig copy.
The corrected full dump contains 107 tracks including 17 groups, 400 devices
in track-level chains, 2831 arranger clips, 2527 notes, and 10 cue markers.
Earlier 664-clip/776-note figures were incomplete and are superseded.
The native scene reader found 10 scenes and 1070 launcher slots.

Every track has a title-based `path`; use `master`, `fx/Name`, or a nested
group path. Duplicate sibling titles resolve to the first match. This project
has 105 unique paths for 107 tracks because `Atmos/Pad` and
`Transitions/Phase Plant` each occur twice. Titles containing `/`, such as
`Leads/Vocals`, resolve using the longest matching literal title. Polling checks
covered 105 track reads, 84 clip reads, 82 device reads, and 96 enumerated
automation lanes through path-addressed RPCs.

Native device reads include identity and numeric/boolean/enum parameter values.
Nested-chain checks (`YhC` single-slot chains, `AYz` layers, `EhV`/`MU2`
multiband/splitter bands, plugin `Ce`/`vCL` FX and multi-out chains, and `Jnx`
drum pads) expanded 400 track-level devices to 978 recursive devices across 420
single-slot `chain`s, 67 `layer` chains, and 13 `band` chains (including one
plugin with a nested FX chain device on `master#dev[5]/band:0[2]`), with no
truncation or cycle references. Recursive reads returned 22726+ native/plugin
parameters, including 8393 plugin parameters.
Plugin reads include device ID, name, vendor, preset fields, and saved
`.vstpreset` state references (`state`, `state_file`, `state_path`,
`state_bytes` via `brS.qP3():File`, resolving all 121/121 plugins across the
project, totaling 357056 bytes). Track `solo` (`roo.fcV().oLP():boolean`) and
`comment` (`roo.lzE():String`), as well as scene `comment` (`eNm.Vc():String`),
are also read natively. Parameter availability is explicit and depends on
resident engine descriptors: an early read found all 77 top-level VST3 caches
empty; after settling, 62 were available and 15 remained
`unavailable_model_cache`, yielding 4182 top-level plugin parameters. An
unavailable cache is not an empty parameter list claim.

Routing reads include native input sources and destinations and plugin
sidechain sources. Six non-null plugin sidechain sources resolved to track
paths and tap points. The final native sidechain gate read 143 `WYJ` components
across recursive devices, including five non-null sources. Note reads include
chance, repeat, occurrence,
and recurrence data, plus 154 expression envelopes containing 316 points.
Audio reads covered 2898 events, 5697 warp markers, and 1425 expression
envelopes containing 1529 points, along with sample references and clip/event
settings. Value-level XML comparisons passed for 105 first-match track paths,
373 device identities, 2821 clips, 2360 notes, all 2898 audio events and 5697
warp markers, and all the expression envelopes/points counted above. Duplicate
paths account for the smaller targeted clip/note comparison subset.
Exports taken before and after the read cycle were unchanged. No project save
was performed.

Each dump track now includes native composite arranger automation targets,
units, and points. Live checks found 105 lanes and 4124 points; all 23 exported
lanes and their 568 points matched, including values. The other 82 lanes are
native data beyond the exporter's target coverage and are reported as
diagnostics rather than parity gaps. Path-addressed `automation.enumerate`
returns targets, units, and integer point counts for this full raw inventory;
the per-track dump contains the point arrays. Its legacy index form
retains mixer/native-double enumeration behavior.

The gated read-only modulator reader passed in the scratch copy: 904 modulators
across 978 recursive devices (zero `read_error`), with `modulators_status` set
to `"native"`. Beyond name, display name, preset, index, and nullable ID, the
reader returned 1811 stored modulator parameters across all 904 modulators (962
`double`, 693 `boolean`, 156 `enum`; units: 867 `linear`, 73 `seconds`, 22
`hertz`, and 849 unitless boolean/enum) and 560 modulation bindings across 316
modulators (component keys: 299 `SOURCE`, 232 `MATH`, 25 `LFO`, and 1 each of
`VELOCITY`, `EG`, `KEYTRACK`, `RANDOM`). All 560 bindings resolved both a
non-empty `target_parameter` path and an exact `{"path", "device"}` (or nested
`{"path", "chain", "device"}`) `target_owner` via atom identity,
`dY3.hMI()`/`dY3.TEI()`, and `kX1.L_()` parent traversal (legacy `dY3.Lg()`
returns null on migrated 6.1.5 documents). The engine process (PID 56272)
remained unchanged across repeated full dumps, both dumps were identical,
targeted `track.read` and `device.read` calls matched the dump, and
before/after `.dawproject` archive entries and structure/value checks passed.
Grid devices and modulators are read only as stored value atoms (no
module/cable graph topology), and plugin parameter targets backed by `GAN`
descriptors rather than `m1C` atoms remain at track-level ownership (`QFL.xTe()`)
when not bound to a `Wcl` owner. Plugin parameter caches can still be
unavailable on any read, as described above.

Each modulator read now also reports its grid cell (`x`, `y`, row `y` in
0..2) and `modulator_id` (the definition's factory UUID, `nJS.ppu().Gcr()`);
each binding reports `amount_normalized` (`AvA.LZe()`: native amount over the
target's modulation span, clamped to -1..1) and `enabled`.

### Modulator editing (live-verified 2026-10-20)

Methods `modulator.catalog` and `device.modulator_add|parameters|bind|unbind|move|delete`
(Python: `JavaSong.modulator_catalog()`, and on track/nested-chain handles
`modulators`, `add_modulator`, `modulator_parameters`, `set_modulator_parameters`,
`bind_modulator`, `unbind_modulator`, `move_modulator`, `delete_modulator`).
All edits use the device's native modulator bank (`JDY`) on the event queue:

- Insert: factory UUID from the packaging table (`MD`/`LXO`, category
  `MODULATOR`, 43 entries), staged with `gDz.tDS(UUID)` and inserted through
  the GUI insertion point's own entry `JDY.IYM(Collection,x,y,clone,F3z)`
  ("Add modulator"). Optional explicit cell must be free; optional initial
  `values` are written to the staged modulator before insertion.
- Parameters: modulator value atoms wrapped as `ApiDecimalValueAtom`,
  `ApiBooleanValueAtom`, `ApiIndexedValueAtom`; normalized 0..1, booleans and
  in-range enum indexes; one validated batch per undo transaction.
- Mappings: the modulator's `Hx3` source component (auto-selected when it has
  one, otherwise by component key). `AwX.jPB(m1C,double)` creates or updates the
  mapping in Bitwig's own (mergeable) mapping transaction; `AwX.Gcr(m1C)` is
  native "Clear modulation". Amounts are -1..1 of the target's span
  (`bL.Nwn()`). Targets are `CONTENTS`-relative paths on the owner device or a
  device inside its nested chains (e.g.
  `CHAIN_LIST/CHAIN0/DEVICE_CHAIN/0/CONTENTS/AMPLITUDE`), or a same-chain
  `target_device`. Bitwig's scope check `Hx3.IYM(sQi)` runs first: a sibling
  device on the same track is rejected (live-verified).
- Move: free cell via native "Move Modulators"; delete: `KaT.IYM(List)`,
  which removes the modulator and its mappings.

Gate `tests/live_java_modulators.py` (evidence
`scratch/editing-modulators/gate-5/`) passed: a 440 Hz sine through Tool with a
unipolar LFO mapped to `AMPLITUDE` measured 0.37 dB envelope depth unmapped,
35.3 dB at amount -0.5, 10.6 dB at -0.15, 0.0 dB with LFO `DEPTH` 0, 0.0 dB
after unmapping and 0.04 dB after deleting the modulator. An FX Layer LFO
mapped into its layer's nested Tool moved a 660 Hz sine by 34.8 dB. Load phase:
during playback, three Polysynth tracks received 45 modulator insertions,
180 mapping writes, 27 deletions and 18 unmappings; the audio-engine process
stayed alive and the Tone mapping still measured 35.2 dB. 21 rejected inputs
(unknown/invalid UUIDs, occupied or invalid cells, unknown/out-of-range/
mixed-invalid parameter batches, out-of-range amounts, unknown targets and
sources, out-of-range target devices, missing mappings) left the dump
unchanged; a sibling-device target was rejected with the device read unchanged. Native
save/reopen: zero reader differences across 27 modulators and 41 mappings;
reopened depths 36.0 dB (440 Hz) and 35.5 dB (nested 660 Hz).

**Engine timing limitation:** a modulator parameter write sent as a separate
event immediately after insertion was dropped by the engine while the document
kept it (0 ms: 3/3 failed; 50, 150, 300 and 500 ms: 2/2 each passed;
`scratch/editing-modulators/diag-insert-race-*`). `add_modulator(values=...)`
stages values before insertion (3/3 passed without pacing) and the Python call
waits a measured 250 ms after insertion (3/3 passed). This is pacing, not an
engine acknowledgement. Not covered: undo/redo replay, mapping
enable/transfer-function/auxiliary scaling, track remote-control and
mixer/modulator-parameter targets, plugin-hosted modulators, and `unit=` reads
for modulator parameters (an explicit `HERTZ` returned unconverted values).

The live synthetic fixture capture (`tests/live_java_reader_fixture.py --capture`)
passed on a disposable scratch project. It retained only three newly authored
tracks and a sanitized master, producing a four-track/two-note fixture with
FM-4; no existing project was cleared or saved. That separate check verified
the effect-track path read against the dump and a send amount of 0.3 with
`AUTO` mode, effective `POST`, matching the export's post-fader send type.
The final released-project native sidechain gate also preserved equal
before/after exports and passed structure/value checks. Native component and
plugin sidechain counts above describe separate representations.

Final verification: 94 unit tests passed, the Java bridge smoke check passed,
and the controller JavaScript syntax check passed. Exact-build reader getter
signatures and their evidence are recorded in [READER_SIGNATURES.md](READER_SIGNATURES.md).
These results establish the listed coverage, not complete native schema parity.

```python
from openwig import JavaSong
from openwig.java_read import export_to_dict, parity_report

with JavaSong() as song:
    dump = song.dump()
    master = song.track_at("master")
    track = master.read()
    device = master.read_device(0)
    lanes = master.enumerate_automation()
    # On a track containing an arranger clip:
    # clip = song.track_at("Group/Track").read_clip(0)
    exported = export_to_dict(song)
    report = parity_report(dump, exported)
```

These readers run on demand; the whole-project dump composes shared readers
in one document event. The parity report identifies structural mismatches and
distinguishes native, partial, unavailable, and export-only fields.

`project.export_dawproject` writes the open project as a Bitwig
`.dawproject` archive (the open XML exchange format) using Bitwig's own
exporter. Python: `JavaSong.export_dawproject(path, embed_media=False)`
(relative paths resolve against the caller's directory). The structure-only
export of the released project was 139 KB. It remains the interchange format and
reader parity baseline, including automation and plugin data where the live model
reader remains partial. Its XML clip count includes nested content and launcher
instances, so it differs from the arranger-clip count above.
`embed_media=True` also embeds referenced
sample files. The project is not modified, and `import_dawproject` provides limited append-style interchange.

For the clip call, send an explicit zero-based main-track index and beat-based
start and duration. Notes are `[channel, key, start, duration, velocity]`,
where each start is relative to the clip. The endpoint uses the same framed
JSON-RPC protocol as `BridgeClient` and runs on port 7778.

The Python package exposes `JavaSong` for this tested subset. Open a project
in the patched Bitwig copy, then run:

```python
from openwig import JavaSong, Note

with JavaSong(tempo=128, bars=4) as song:
    song.track("Java Probe", device="FM-4").volume(0.7).pan(0).clip([
        Note(60, 0, dur=1), Note(64, 1, dur=1),
    ])
```

`JavaSong` currently appends instrument tracks, native factory devices, and
MIDI arranger clips. A `JavaTrack` can call `list_parameters(0)`,
`parameters(0, OUTPUT=0.35)`, and `get_parameters(0, ["OUTPUT"])` after its
native device has loaded. Boolean values and enum integer indexes are also
supported; enum writes passed the disposable-project live check.

`JavaSong.play(loop=True)`, `stop()`, and `transport()` use the Java playback
endpoints. `render("out.wav", tail=1.0)` records one arrangement
pass through the existing system audio loopback backend and returns
`{path, seconds, rate, channels, rms, silent}`. This takes real time, records
the default audio output (including other applications), and requires
Bitwig to send audio to that output. It is not Bitwig's offline export.
Bitwig's native tempo map supplies the capture duration, so tempo automation
is included without changing the tempo during render setup. The Java
patch targets Windows; the shared PulseAudio/PipeWire capture backend remains
available to the controller path on Linux. Capture failures stop playback and
release the audio device.

Transport playback, stopping, position, loop readback, and audible rendering
passed live checks (FM-4 note render: 2.5 s, 48 kHz stereo, rms 0.103); play/stop
state can take about 0.2 seconds to settle. Loopback capture records the
default output, so a muted or powered-off default endpoint yields a silent
WAV; `render` warns in that case. Repeat the check in an open disposable
project using `$env:PYTHONPATH="src"; python tests\live_java_render.py`.
That check creates and deletes one temporary FM-4 track, checks playback and
loop readback, and writes `scratch/java-render-smoke.wav`. It does not clear or
save the project. Use an empty disposable project so existing tracks cannot
produce a false positive for the non-silence check.

Launcher clip writing is outside the current migration scope; launcher slots
and clips can be read. `JavaSong.clear()` clears the active project of
main/effect tracks and master devices. Use it on a disposable project while
the bridge is experimental. `JavaSong.clear_main_tracks()` and
`JavaSong.clear_effect_tracks()` expose the individual clear operations.

## Install and restore

Close Bitwig first. A live install under `C:\Program Files` requires a Windows
account with write access there. The installer validates the exact original
hash and the changed JAR entries, keeps an original backup beside the JAR,
and replaces the JAR atomically.

```powershell
python java_bridge\install.py install --patched scratch\bitwig-6.1.5-openwig.jar
python java_bridge\install.py status
python java_bridge\install.py restore
```

Use `--bitwig-root` to target a separate Bitwig copy. `--assume-closed` is for
an isolated copy when its processes are known to be stopped; it bypasses the
process check. A Bitwig update changes the JAR and requires a new verified
patch; the installer rejects an unknown original.

## Next step

Address remaining schema field gaps and unavailable plugin caches as separate
verified items. Modulator mappings/parameters and Grid internals remain outside the
verified inventory boundary.
Keep unavailable plugin parameter caches explicit. See `JAVA_BRIDGE_GAPS.md`
and `READER_PARITY_DESIGN.md` for verified coverage and remaining work.
Full-project native writing is separately authorized and described below;
catalog capture remains outside the current scope.
The main Song API still targets the controller on port 7777.

## Full native project writing (6.1.5)

```python
from openwig import JavaSong
from openwig.project_archive import write_project

with JavaSong() as song:
    song.save_project("output/song.bwproject")  # includes unsaved SDK edits
    song.export_project("song.openwig.zip")   # native document + media + state

restored = write_project("song.openwig.zip", "restored-song")  # works offline
print(restored["path"])  # open this .bwproject in Bitwig 6.1.5
```

Both output files and the restored directory must be new. Saving serializes a
detached copy, collects media, embeds plugin state, and creates the native project
folder marker. It leaves the original project's name/path and file unchanged.
The ZIP manifest checks every resource's SHA-256 and size during restoration.
`JavaSong.write_project(archive, directory)` exposes the same offline restore.
`projects()`, `project_info()`, and `select_project(path)` address open project
tabs explicitly; all composition/read calls now use the selected tab.

The full live roundtrip retained 107 tracks, 978 recursive devices, 2831 clips,
2527 notes, 2898 audio events, 10 scenes and 10 markers. The settled audit found zero reader-visible differences after verifying
relocated audio by content hash and all 120 embedded plugin-state blobs.
The source dump and original file hash stayed unchanged. See
[`JAVA_PROJECT_WRITE_DESIGN.md`](../JAVA_PROJECT_WRITE_DESIGN.md) for comparison
rules and the manual capture/restore/audit commands in `tests/live_java_recreate.py`.
Runtime plugin cache availability is reported separately. Third-party plugins
and licensed packages must still be installed. Native serialization preserves
opaque state; JSON-only project synthesis and independent Grid/filled-launcher
validation remain outside the verified scope. Standard DAWproject import can
lose native devices, automation and audio properties and is not this full-project
restore path.


`JavaSong.duplicate_range(start, end)` repeats an arranger range and pushes later
material by its length (times in beats). It calls Bitwig's native Duplicate and
Push operation, which owns its undo transaction and handles crossing clips,
automation and cue markers. A disposable live check duplicated 0..4 beats:
clips at 0 and 8 became 0, 4 and 12; the marker and later automation moved from
8 to 12. The music-variation check repeated 304..336 twice in a copied full
project, moved the vocal entrance 336 to 400 and END 792 to 856, and retained
107 existing tracks before adding the new synth. Use a project copy for
arrangement experiments.


`JavaSong.insert_time(start, beats)` inserts empty arranger time;
`delete_range(start, end)` deletes and pulls later material left. All times are
beats. The exact-build native commands own their undo transactions and include
nested clips, effect/master automation, and cue markers. Transport loop bounds
are unchanged and should be adjusted explicitly if needed. The manual gate
`tests/live_java_ranges.py --disposable --project <open-disposable.bwproject>`
clears its explicitly selected project and verifies a crossing clip (2..8), later
clip (12..16), effect/master automation and marker: inserting 4 beats at beat 4
produces clips 2..4, 8..12, 16..20; deleting 4..8 pulls them to 2..4, 4..8, 12..16.
Native splitting leaves adjacent fragments after the inverse operation.

Arranger automation may start after beat zero. Bitwig's implicit initial anchor
is preserved; caller-supplied points are matched by time before applying their
normalized values, curvature and interpolation. The same live gate verifies
nonzero-start automation at 8/16 beats, including a hold and curved segment.

The range/automation synthetic fixture also passed native save/reopen with zero
clip/automation/marker differences. Hold segments canonicalize curvature to zero
immediately, matching Bitwig serialization; linear curvature is retained.

## Verified arranger clip editing and audio import

`JavaTrack.update_clip(index, **changes)` edits `start`, `duration`, `name`,
`muted`, `color`, `loop`, `loop_start`, `loop_duration`, `play_start_offset`,
`fade_in`, and `fade_out`. Times/fades are beats; fades require audio clips.
Resizing trims the container without rewriting its content. Moves maintain
native timeline sorting, so indexes can change. Overlaps with other clips are
rejected before mutation. Palette colors may snap. `delete_clip(index)` deletes
one arranger clip without moving later material. Native undo wraps both edits.

`JavaTrack.import_audio(file, start=0, duration=None, source_offset=0,
gain_db=0, name=None)` creates a RAW audio clip from an existing local file.
Start/duration are beats, source offset is seconds, gain is decibels. Default
duration maps the remaining source seconds through the native project tempo
map. Returns `{"clip": sorted_index, "data": clip_snapshot}`. Rejects overlaps,
nonfinite values, unavailable sources and offsets beyond the source end.
Audio event editing, reverse and warp/pitch state authoring are described below;
immediate processing transitions still have an explicit runtime limitation.

Manual evidence: `tests/live_java_clips.py --disposable --project <open-fixture>
--output <fresh-directory> [--render]`. Reopen its saved project, then run with
`--verify` and the same output. The synthetic gate checks retained MIDI content,
move/resize/sorting, rejected edits, deletion, RAW source offset/gain, fades,
collected media and full reader-visible persistence. Loopback includes other
applications; close unrelated audio for spectral playback checks.

Native save now matches clone media by persisted source identity instead of
list position: serialization removes unused media left by clip deletion.
Ambiguous or unmatched identities fail explicitly. This also fixes saving after
clearing a project with formerly referenced media.

## Native copy/split and expressive MIDI replacement

`track.copy_clip(index, start, destination=None)` copies a complete arranger clip
at an absolute beat position. Destination defaults to the source track and must
belong to the same `JavaSong`. Returns the destination's sorted `clip` index and
`data`. Native content/settings are retained; content is made independent so
later edits do not mutate the source. Overlaps are rejected.

`track.split_clip(index, beat)` splits strictly inside a clip using an absolute
arranger beat. Returns `{"clips": [{"clip": index, "data": snapshot}, ...]}`.
Native splitting preserves note/audio content, adjusts the right play offset,
and removes interior audio fades. Indexes may change after timeline edits.

`track.replace_notes(index, notes)` replaces a MIDI clip's content; `[]` clears
it. Each note is a dictionary with `key` (0..127) and positive beat `duration`.
Optional fields: `channel` (0..15, default 0), `start` (local beats, default 0),
`on_velocity` (.8), `off_velocity` (.5), `muted` (False), `chance` (1). Velocities
and chance use 0..1. Container settings and other clips are preserved, including
when the original content was shared by a copy/split.

`expressions` optionally maps `gain`, `pan`, `transpose`, `timbre`, `pressure`
to `{"unit": "<Bitwig Unit name>", "points": [[beat, value], ...]}`.
Points interpolate linearly, have increasing local beats within note duration,
and use explicit units. For example pressure `PERCENTAGE_OF_RANGE` values
20..80 store .2...8; transpose `SEMITONES` 0..7; gain `DECIBEL` -6..0.
This writes MIDI pitch expression; audio pitch state authoring and its runtime
limitation are described below. Recurrence/repeat/occurrence controls and arbitrary curve
shapes are not yet exposed by note replacement. Unknown fields fail explicitly.
All notes/expressions are staged off-document before replacement, so malformed
later notes cannot erase earlier content.

Manual gate: `tests/live_java_content.py --disposable --project <open fixture>
--output <fresh scratch directory>`. Reopen its saved project and run `--verify`
with the same output directory. Includes independent/cross-track copies,
MIDI/audio splits, five expression types, invalid requests, and native persistence.

## Editing events inside audio clips

`track.import_audio_event(clip, file, start=0, duration=None, source_offset=0,
gain_db=0)` adds RAW audio to an existing audio clip. Local event start/duration
are beats; source offset is seconds. Default duration maps the source remainder
through the native tempo map at `clip.start + event.start - clip.play_start_offset`
(the first arranger occurrence). Events can extend outside the container; resize
the clip explicitly to expose that content. Overlaps are rejected.

`track.update_audio_event(clip, index, **changes)` edits `start`, `duration`,
`source_offset`, `gain_db`, `muted`, `reverse`, `fade_in`, `fade_out`. Event times
and fades are beats, source offset is seconds, gain is decibels. A gain write
replaces the existing gain-expression curve with a constant. Reverse supports
RAW and warped audio. RAW offsets require forward audio; warped source-second
offsets follow the active forward or reversed warp mapping. Change offsets and
reverse separately. Supplying both `source_offset` and `content_offset` is
rejected because they address the same position in different units. The native reverse operation preserves the selected source
window, adjusts playback endpoints and mirrors native expression state.

Both methods return `{"event": sorted_index, "data": event_snapshot}`.
Moves can change indexes. `track.delete_audio_event(clip, index)` removes exactly
one event and returns deleted index/remaining count. It leaves the clip container
and other events in place; importing into the emptied lane is supported.
These operations unlink shared content before mutation and run in native undo
transactions. Invalid ranges/fades/offsets and overlaps are rejected before edits.
Warp/pitch state writes are described below. General audio expression curves
remain pending.

Manual gate: `tests/live_java_audio_events.py --disposable --project <open fixture>
--output <fresh scratch directory> --render [--tempo 150]`. Reopen its saved file
and run with `--verify --render` and the same output. Authored 440/880 Hz source
proves reverse audibly at 120/150 BPM; the reopened 150 BPM fixture retains all
reader-visible state and reversed 880/440 Hz output. System loopback includes
other applications, so recordings require a quiet output. Document commits
precede engine updates; the gate gives sample processing one second to settle.

Copied clips now invoke native insertion finalization after attachment and
make-unique. This fixes inconsistent first playback that metadata-only copy
checks missed. The source's explicit fade-in time/gain/curve are restored after
the callback initializes automatic defaults. The existing native content gate
checks copied fades and full MIDI/audio persistence as a regression.

## Warp and audio-processing state authoring

`update_audio_event` also accepts:

- `warp_mode`: RAW, STRETCH, STRETCH_HD, REPITCH, SLICE, CYCLIC,
  ELASTIQUE_PRO, ELASTIQUE, ELASTIQUE_ECO, ELASTIQUE_SOLO.
- `warp_markers`: at least two `[local_beat, source_seconds]` pairs, strictly
  increasing in both dimensions, within the source file. RAW-to-warp changes
  require explicit markers. Apply native `reverse=True` to reverse a mapping;
  replacing a reversed mapping first requires setting it forward.
- `content_offset`: local beats within the warp mapping. RAW uses source_offset
  seconds instead. Warped `source_offset` seconds are converted through the
  active mapping, including reversed maps. Existing reversed material must be
  set forward before a new warp mapping is applied.
- `transpose`, `formant`: semitones (-96..96); `pan`: -1..1. These replace their
  respective expression curves with constants. Transpose rejects RAW/REPITCH,
  whose algorithms do not apply the requested independent pitch processing.
- `preserve_formants`, `preview_onsets`: booleans.
- `formant_envelope_resolution`: integer 8..512; out-of-range inputs are rejected
  rather than silently accepting Bitwig's native clamp.
- `preserve_transients_mode`: OFF/SOFT/HARD; `transient_rate`:
  ONSETS/THIRTYSECOND/SIXTEENTH/EIGHT/QUARTER (EIGHT is the native spelling).
- `warp_grain_size`: SMALL/NORMAL/LARGE; `warp_tail_mode`: NONE/GRAIN/PINGPONG.
  DSP effects of processing settings depend on the chosen algorithm.

Example: `track.update_audio_event(0, 0, warp_mode="ELASTIQUE_PRO",
warp_markers=[[0, 0], [5, 1], [7.5, 3]], content_offset=0, transpose=7,
preserve_formants=False)`.

Immediate stretch, REPITCH, and semitone-shift playback now pass without a
save/reopen. The former apparent processing-cache regression came from track
deletion: removing only a visible track reference left its owning object alive
and audible. Old tracks then mixed with newly authored fixtures. Deletion now
removes both visible and owning bank memberships, matching the native Delete
Tracks sequence for main and effect tracks. No warp-cache workaround or native
engine disassembly was needed. General audio expression curves remain pending.

Manual gate: `tests/live_java_warps.py --disposable --project <open fixture>
--output <fresh directory> --live-render --repeat 3`. Each iteration saves a
fresh fixture, checks immediate stretch (440/880 Hz), REPITCH (220/1760 Hz), and
+7 semitones (about 660/1316 Hz), then clears it and rejects lingering fixture
tones. It also covers nested groups and effect-track deletion. With `--repeat`,
fixtures live in `run-1`, `run-2`, etc. Reopen a run's `saved/project.bwproject`
and pass that run directory to `--verify --render` for persistence/readback.
All ten algorithm names are read back; audible certification covers the three
processing cases above, not every algorithm/settings combination.

The default capture uses system loopback, so other apps must be quiet. On
Windows, `tests/windows_process_loopback.cpp` is an optional manual-test helper
that records only the supplied audio-engine PID (not the launcher PID). Build
from a Visual Studio x64 developer prompt with the installed Windows SDK:

```bat
cl /EHsc /std:c++17 /DNTDDI_VERSION=0x0A00000A tests\windows_process_loopback.cpp /Foscratch\process_loopback.obj /Fescratch\process_loopback.exe /link ole32.lib mmdevapi.lib
```

Then append `--process-capture scratch/process_loopback.exe --engine-pid <PID>`
to the gate. Activate audio before identifying its process; engine PIDs change
on restart. Capture remains a manual test aid, not a change to `JavaSong.render`.
The deletion check measures lingering fixture tones rather than endpoint-wide
silence, allowing Bitwig's metronome clicks. Only use disposable projects.

## Launcher clip slots (2026-10-19)

Live-verified launcher surface on a disposable synthetic project:

- `track.read_launcher_slot(slot)` returns the stop button, full clip data
  (including launch settings), and runtime `playing`/`queued` state.
- `track.put_launcher_notes(slot, notes, duration, name, loop, has_stop,
  replace)` authors a MIDI clip; note dictionaries match `replace_notes`.
  `track.import_launcher_audio(slot, file, ...)` authors a RAW audio clip.
  `track.copy_arranger_to_launcher(slot, clip, ...)` copies an arranger clip
  of the same track into a slot, preserving native settings and making
  content independent. Occupied slots require `replace=True`; rejected
  writes leave the slot untouched.
- `track.update_launch_settings(slot, ...)` writes `launch_quantization`
  (DEFAULT/OFF/BARS_8..SIXTEENTH), `launch_mode` (DEFAULT/FROM_START/
  CONTINUE_OR_FROM_START/CONTINUE_OR_SYNCED/SYNCED), `next_action` (STOP,
  RETURN_TO_ARRANGEMENT, PLAY_* and block variants), `next_action_time`,
  `use_loop_start_as_quantization_reference`, and the slot's `has_stop`.
  Settings are readable through every clip reader (`launch_settings`).
  Arranger clips hold them too (they apply once the clip is in a slot), so
  `clip.update` (`update_clip`, `update_launcher_clip`) takes the same
  settings except `has_stop`.
- `track.update_launcher_clip(slot, **changes)` and
  `delete_launcher_clip(slot)` address slot clips through the arranger clip
  operations; launcher clips have no arranger `start`. The audio-event
  methods accept `slot=` instead of a clip index for shared content edits.
- `track.launch_slot(slot)` triggers a launch, `stop_launcher(immediate=)`
  stops the track's launcher playback, and `return_to_arranger()` ends it.
  Clearing uses Bitwig's native nullable slot setter because the public API
  wrapper's `setClip(null)` throws.

Playback needs the audio engine connected: headless launches can start with
it disconnected, in which case slots refuse to launch and transport cannot
play (tracks stay engine-inactive). `document.audio_status` /
`document.activate_audio` connect it; the gate activates before launching.

Manual gate: `tests/live_java_launcher.py --disposable --project <open
fixture> --output <fresh directory> [--render]`, then reopen the saved file
and rerun with `--verify --render`. The 120 BPM gate authored a MIDI slot
with launch settings, an audio slot from a synthetic 440 Hz tone, and an
arranger copy; it verified occupied-slot rejection, atomicity, content
independence, shared audio-event edits through slot addressing, container
resize, clear/delete with stop-button control, out-of-range slot rejection,
launch/stop/return playback states, and an audible 440 Hz render while the
slot loops. Save/reopen produced zero reader differences and the reopened
slot launched and rendered the tone again. Evidence:
`scratch/editing-launcher-gate-2/` (`before.json`, `persistence.json`,
`launch.wav`, `reopened-launch.wav`).


## Warped reverse and source-second offsets (2026-10-19)

`tests/live_java_audio_details.py --disposable --project <open scratch file>
--output <fresh directory> [--process-capture <helper> --engine-pid <PID>]`
checks a nonlinear three-tone mapping at 150 BPM, native reverse twice restoring
the original clip, forward/reversed source-second offsets, corrected mapped
playback endpoints, formant resolution, rejected-edit atomicity, independent
copies and launcher content addressing. Reopen its saved project, then use
`--verify` with the same output and capture options. Gate evidence:
`scratch/editing-details/gate-1/`; native persistence has zero reader differences
and reopened reversed-offset audio retains the expected 1320/880/440 Hz order.
`sample_play_stop_seconds` now follows the warped mapping and file bounds instead
of reporting the inactive RAW stop field on reversed warped events.

## Individual notes, conditions and expression curves (2026-10-19)

`JavaTrack.import_note(clip, data=...)`, `update_note(clip, index, **changes)`
and `delete_note(clip, index)` edit one MIDI event. Use `slot=` instead of `clip`
for launcher content. Edits make shared content independent and return the
updated sorted index/data; deletion returns the remaining count.

Note dictionaries additionally support `velocity_spread` (0..1), `content_start`
(beats), `chance` (0..1), `occurrence` (the native named enum), `recurrence`
(packed integer 0..2047), `repeat_count` (-127..127), `repeat_curve` (-1..1),
and `chance_enabled/occurrence_enabled/recurrence_enabled/repeat_enabled`.
Recurrence packs period-minus-one in bits 0..2 and enabled iterations in bits
3..10. These condition fields also work on `update_audio_event`.

`expressions` maps a name to `{"unit": "DECIBEL", "points": [[0, -6, .3],
[2, 0, 0, "hold"]]}`. Points are `[content_beat, value, curvature?, interpolation?]`,
strictly increasing, with curvature -1..1 and interpolation `linear`/`hold`.
Empty points clear a curve. Updating one named curve preserves the others.
MIDI supports gain/pan/transpose/timbre/pressure; audio supports gain/pan/
transpose/formant (transpose needs a pitch-processing warp mode).
Expression points use **beats even in RAW audio**; RAW source offsets still
use seconds. Curves can extend past a trimmed note's visible duration.

Manual gate: `tests/live_java_note_details.py --disposable --project <scratch
file> --output <directory> [--process-capture <helper> --engine-pid <PID>]`.
Reopen the saved file and rerun with `--verify`. Evidence in
`scratch/editing-notes/gate-2/` covers all five MIDI curves, shapes, clearing,
other-field preservation, copy independence, note insertion/deletion,
rejection atomicity, warped audio curves/conditions and audible RAW envelope
timing before and after reopen. Native persistence comparison is exact under
the existing reader audit's documented numeric/media rules.

## VST3 insertion, parameters and preset transfer (2026-10-19)

`track.insert_plugin(name, plugin_id, state=None)` appends a VST3 using its exact
32-hex class ID. Optional state must be a VST3 preset with the same header class
ID. The result includes the sorted device index and initial snapshot; plugin
loading and resident parameter discovery are asynchronous. This increment does
not provide a plugin catalog or VST2/CLAP insertion.

`plugin_parameters(index)` reports resident parameter IDs, normalized values,
native load state and state dirtiness. Missing caches remain explicitly unavailable.
`set_plugin_parameters(index, {id: value, ...})` validates the whole batch, writes
0..1 values and captures state for native project copying. **It currently waits
100 ms before capture.** Immediate native capture can save the previous value;
its completion callback is not an engine-update fence. This pacing is verified
on this host with kHs Haas, and is an open limitation for broader plugin coverage.

`export_plugin_state(index, file)` writes current VST3 engine state to a fresh
`.vstpreset`, waiting for native async completion. `capture_plugin_state(index)`
captures into Bitwig's state pool so project copies embed the blob; it waits for
loaded plugins and existing captures. Neither operation overwrites an owner preset.

Gate `tests/live_java_plugins.py --disposable --project <open scratch project>
--output <fresh directory> [--process-capture <helper> --engine-pid <PID>]`, then
reopen the saved file and repeat with `--verify`. `scratch/editing-plugins/gate-16/`
checks nondefault parameters, independent preset restoration, invalid-batch
atomicity, state header mismatch rejection, existing-file protection, audible
440 Hz playback, native reopen and matching embedded state hashes. Its only
reader differences are two stale GUI display strings; numeric values match.

## Nested device chains (2026-10-19)

`track.chain(device, component_path)` returns a device-only handle. The path is
copied from `read_device(...)["chains"][...]["component_path"]`; calling `chain`
on a handle addresses another level. All shared device operations use the
selected chain, including factory insertion, native parameters and deletion.
`chain_banks(device)` discovers parallel containers; `create_layer(device,
component)` appends a native layer and returns its new component address.
Device indexes remain positional and should be refreshed after structural edits.
See the parallel-layer controls section below for mixer metadata, removal and reordering.

Example: `track.chain(0, "CONTENTS/CHAIN_LIST/CHAIN0").add_device("Tool")`.
Poly Grid pre/post chains use `CONTENTS/PRE_FX/Chain` and
`CONTENTS/POST_FX/Chain`; editing those chains does not edit Grid modules.

`tests/live_java_device_chains.py` covers deep FX Layer authoring, a parallel
layer, native parameter/name/enable/delete operations, Grid fixed chains,
invalid-address atomicity, audible -6 dB gain relative to bypass, and native
save/reopen with zero reader differences. Evidence: `scratch/editing-chains/gate-2/`.
Nested VST3 methods share the same addressing but have not yet had their own
plugin live gate.

## Grid modules, cables and parameters (2026-10-19)

`read_grid(device=0)` returns modules with factory `module_id` UUID, positional
`component` address, name, x/y/width/height, inputs (including connected `source`)
and outputs. These also appear under `read_device(...)["grid"]`.
`add_grid_module(device, module_id, x, y)`, `move_grid_module(device, component,
x, y)`, `delete_grid_module(device, component)` and `connect_grid(device, input,
output)` edit the native graph; `output=None` disconnects. Refresh addresses after
deletion. Creation uses factory UUIDs, obtainable from existing module reads;
there is no public module catalog yet. Coordinates must be integers within
+/-1000000; occupied cells reject, and the bridge bounds insertion at 4096 modules.

`list_parameters(device)` includes `MODULES/0/CONTENTS/...` paths. Pass those
names to `parameters`/`get_parameters`, with explicit native units as needed.
Example: `track.parameters(0, unit="RATIO", **{"MODULES/0/CONTENTS/PITCH": 2})`.

Manual gate `tests/live_java_grid.py` uses the usual --project/--output/--disposable
and process-capture arguments, then --verify after reopening its saved project.
`scratch/editing-grid/gate-3/` verifies a rebuilt Union/ADSR/Audio Out graph,
positions, cable cleanup, invalid edits, audible fixed-pitch C4/C5, disconnect
silence and zero reader differences after reopen. 249 Python tests pass.

**Open limitation:** live note-tracked oscillator pitch can be wrong after an
in-session parameter edit, although the saved document and reopened sound are
correct. The gate's `--note-tracking` option retains this failing regression.
The default passing gate explicitly disables KEYTRACK. Save/reopen before
trusting note-tracked Grid renders; do not infer reliable live note tracking
from the fixed-pitch gate. This is separate from the resolved orphan-track warp bug.

## Parallel layer controls (2026-10-19)

`read_layer(device, component)` reads stored `name`, display `title`, and an
optional `mixer` (amplitude `volume`, `volume_db`, bipolar `pan`, `muted`). A
zero-amplitude fader reports `volume_db: null`. Nested device snapshots include
this state under each chain's `layer` field.
`update_layer(device, component, name="Quiet", volume_db=-6, pan=0, muted=False)`
patches selected fields. Choose amplitude `volume` (0..+6 dB equivalent) or
`volume_db` (-120..6), and pan -1..1; invalid mixed batches reject before writing.

`move_layer(device, component, index)` waits for native asynchronous completion
and returns the new component address. `delete_layer(device, component)` removes
one parallel layer with native routing/ownership cleanup. Structural edits require
an AYz parallel-layer bank; fixed pre/post chains cannot be deleted/reordered.
Refresh positional chain handles after either operation.

Expanded gate `tests/live_java_device_chains.py`, evidence
`scratch/editing-chains/gate-4-layers/`: mixer/name edits, reorder both directions,
audible deletion ratio, invalid batches, zero reader differences after native
reopen, and audible reopened processing. 259 Python tests pass.
`tests/live_java_plugins.py --nested` additionally exercises two VST3s inside a
layer moved to index 1, including state transfer, native save/reopen and audio.
`scratch/editing-plugins/nested-gate-2/` passes this gate, including deletion of a
third plugin-bearing layer after native state preparation. Parameter values and
state hashes match after reopen; the same two GUI display-string differences as
the top-level plugin gate are reported explicitly. Deletion waits for Bitwig's
native plugin-state preparation before beginning its removal transaction.

The open note-tracked pitch regression also appeared on newly inserted Polysynth;
its cause is not yet proven to be Grid-specific. The fixed-pitch Grid gate remains
the verified immediate-DSP case. See the plan for diagnostic evidence and removed
candidate fixes.

## Native project open and close (2026-10-20)

`document.open {path, activate_engine}` opens a project in a new tab through
Bitwig's own entry `eY1.IYM(String, int tabIndex, Set<rLC>{SELECT[, ACTIVATE_ENGINE]}, F3z)`
and returns a job; poll `document.job_status {job}`. A project that is already
open is selected instead (`already_open: true`). The file must sit in a project
folder: without the `.bitwig-project` marker next to it Bitwig never finishes
opening (the job stays `running`, and a command-line open is silently dropped),
so the bridge refuses such files. Editions without multiple projects refuse
while a tab is open (`H3n().Klm()`).

`document.close {path | unsaved, discard_changes: true}` closes a tab through
`eY1.IYM(aBx, false, null, rdP)`, which stops the tab's engine and skips the
interactive save branch: unsaved edits are lost and nothing is written.
`unsaved` names a never-saved tab, such as the "New 1" a headless start opens
from the startup template. `discard_changes` is required.

Python: `JavaSong.open_project(path, activate_engine=False)`,
`close_project(path, discard_changes=True)`, `close_unsaved(name,
discard_changes=True)`, `projects()`, `select_project(path)`. Opening a project
saved by another Bitwig version makes Bitwig copy it to a version backup next to
the file (`eY1.eUA(File)`), so open copies of projects you care about.

Gate `tests/live_java_documents.py` (evidence
`scratch/document-open/gate-5/out/report.json`) passed on disposable copies:
open in 0.8 s next to another open project, already-open detection, a probe
track discarded on close (0.56 s; file byte-identical, edit gone on reopen),
eight invalid requests refused (closing a closed or unknown tab, close without
discard, unknown unsaved tab, missing file, non-project file, a `.bwproject`
outside a project folder, unknown job), the startup template's unsaved "New 1"
closed, and a reopen from zero tabs. The `--older` variant was not run.

## Project reconstruction from reader dumps (2026-10-21)

`openwig.reconstruct.Reconstructor(song, dump, states=dir).run()` rebuilds a
`JavaSong.dump()` into the selected, empty project through native edits and
returns a report: what was written, what the writer cannot express (counted,
with an example each) and what failed. Plugin states resolve from `states` by
file name, media through an optional `media` mapping. Tracks are created with
temporary unique titles, so duplicate sibling names stay addressable, and get
their real titles last.

Bridge methods added for it (all path-addressed, `chain` steps for nested chains):

- `device.insert_factory {id}`: native device by definition UUID (the reader's
  `device_id`; renamed and preset devices keep their definition's UUID).
- `device.insert_plugin {format: vst2|clap, id, state}`: VST2 through the API
  preset (`.fxb`/`.fxp` state) and CLAP built like Bitwig's DAWproject importer
  (`cq.IYM(name, pby.jPB("clap:<id>"), version, file)` with a copy of the
  `.clap-preset` in the plugin-states folder). VST3 takes `role`
  (`instrument`, `audioFX`, `noteFX`, `analyzer`); without it a VST3 instrument
  arrives as an audio effect.
- `device.create_band {component}`: a chain in any chain bank (FX/Instrument
  Selector), `device.drum_pad {key, name?}`: a Drum Machine pad chain
  (`ApiDrumPads.getDrumPad(key).getDeviceChain()`).
- `device.set_active {on}`, `device.component_rename {component, name}` (Grid
  modules), `device.modulator_rename {modulator, name}` (the user name,
  `urr.qFH`; an empty name restores the numbered title) and, with `parameter`,
  a parameter label (`pxJ.tDS`, a Macro knob named "0 to 1"; on a Macro knob
  also its name panel value, `HEi.kAg().Qcu`).
- `device.modulator_add`/`device.modulator_parameters` take `unit` for staged
  values and `bipolar` ({param: bool}) for Macro knobs (the knob's bipolar
  panel value `HEi.tY1().Gcr`, then `HEi.AOA`), applied before values;
  booleans and enums are applied before numbers.
- `device.modulator_bind {target_component, target_device}` reaches devices
  inside the modulator owner's nested chains, `target_parameter` may be
  `MODULATORS/<i>/CONTENTS/<param>` (modulator-to-modulator), and
  `native_amount` keeps amounts beyond the clamped -1..1 normalized range.
- `device.set_sidechain {component, source: {path, tap} | null}` and
  `track.set_source {source}`: taps `pre_fader`, `post_fader`,
  `post_fx_notes`, `pre_fx`, or the track itself.
- `device.load_sample {component, kind: single|multisample, sample | zones}`:
  Sampler content through `ApiSamplerResourceComponent` (file, root key, key
  track, gain, detune, start/end, loop mode/start/end/crossfade; zones with
  key/velocity/select ranges). `device.model_fields` and
  `device.set_model_fields {descriptor, index?, fields}`: stored fields that are
  not parameters, per atom descriptor (`sample_player_atom`: a Sampler's
  playback, repitch, cycles, spectral and grain modes), read and written
  through Bitwig's field descriptors (`kX1.nj_()` → `def.lKq()`, `deb.jPB`).
  Only settable fields (`deP.SOE()`) are written; derived ones come back in
  `skipped`, and runtime identity (`managed_object_ID`) is never reported.
  `device.load_impulse {component, impulse}`:
  Convolution impulses as package references (vendor, package, type, path).
- `automation.write_target {target, kind: real|boolean|enum|integer, points}`:
  any lane the reader reports (`On`, `CONTENTS/...`, nested
  `.../DEVICE_CHAIN/<i>:...`, `MODULATORS/<i>/CONTENTS/VALUE`, mixer targets),
  with raw values; a new lane's beat-zero anchor is removed.
- `transport.set_groove {enabled, shuffle, accent_amount, accent_phase}`,
  `transport.set_time_signature {numerator, denominator}`.
- `device.structure`: modulator identities and chain components without the
  project-wide indexes of `device.read` (fast enough to call per device).
- `project.render {path, start, length, track?, tap?, bit_depth?}` and
  `project.render_status {job}`: offline export through Bitwig's own
  "Exporting audio" job (`Ovo`), as its render tests do; nothing plays through
  the audio device. The job's "open folder" step is disabled. The job takes
  start and end beats (verified: start 4, end 8 renders 4 beats at 150 BPM);
  the bridge passes `start + length`.

The reader now adds normalized modulator values and enum indexes, Macro
polarity and labels, modulator user names (`user_name`, empty when the
modulator shows its numbered title), modulator-to-modulator binding paths,
sampler content (`sample_content`), Convolution impulses (`impulses`) and
stored non-parameter fields (`model_fields`, Sampler player atoms so far).

Writer behavior learned from a full rebuild of a 191-track project:

- Clip fades can be stored negative (Bitwig reports them as such) and audio
  events can start before their clip; `clip.update` and `audio_event.update`
  accept both, as Bitwig stores them.
- `Clip.setPlayStartOffset` moves the play start before the end marker unless
  the target lies beyond it, so a play start exactly on the end marker lands one
  tick early; the reconstructor steps past it first.
- Fresh containers can hold default devices in fixed chains (a Vocoder carrier's
  noise and receiver) and fresh devices default modulators (a Polysynth's LFO and
  Expressions); both are removed before the dump's own are added, so indexes match.
- A Macro inserted with `bipolar` read unipolar after insertion while only the
  knob's own flag was written (the clone re-derives it from the panel value,
  see below); with the panel value written it carries through insertion. The
  reconstructor still sets it again on the inserted modulator.
- Bitwig stores notes that start before the clip content, negative note
  content starts, expression steps (two points at one beat) and transpose
  curves on REPITCH events; the note, expression and audio event writers now
  accept them instead of refusing the whole clip or event.
- Modulator mappings can target a device's On switch and another device's
  modulator (a Math input inside a nested preset); the reader walks up from the
  target atom to its device to keep the `MODULATORS/<i>/CONTENTS/` path, and
  `device.modulator_bind` falls back to the reader's target resolver.
- Stored enum values can exceed the enum's entry count (a modulator Timebase
  reads 22); the staged value is handed to the parameter's setter.
- VST2 presets get no role through the API (`fLo` is built with the default
  audio-effect role), so a VST2 instrument arrived as an effect and received
  no notes; `device.insert_plugin` now sets the descriptor's role
  (`cfH.IYM(eOc)`, the field VST3 presets take in their constructor).
- A plugin's own sidechain input is `vgq.Oof().Idv()` (a `WYJ`, like a native
  sidechain component); `device.set_sidechain` without `component` sets it.
- Track faders go above 0 dB: the reader's percent-of-range volume exceeds 1
  there, and a fader at its top reads 2 (a project stored 1.999999999999999);
  `track.set_volume` takes 0..2 (it stopped at 1.995 before).
- An automation clip made over a note clip's range copies that clip's cue
  markers; its play start is set, read back, and set again from beyond the
  end marker only when Bitwig clamped it.
- Macro modulators keep their mappings in a subclass of `Hx3`; the reader
  matched the class name exactly and so reported no mapping for any Macro (1263
  in one project, among them a master Tool's volume, which left the rebuild
  silent). It now matches the type, as `device.modulator_bind` always did.
- Mapping a Macro relabels its knob after the target; the reconstructor writes
  labels after all mappings.
- Modulator names, Macro polarity and Macro knob labels used to revert when a
  written project was reopened (values and mappings stayed; mapping amounts
  rescaled with the polarity). The file was not at fault: the writer set
  fields Bitwig derives again on load. A modulator's displayed `device_name`
  is recomputed from `user_device_name` (`urr.RLC`/`qFH`) or numbered from
  its definition (`KNf.UG1`); a Macro knob (`TOM`, a `HEi`) copies its
  polarity from its bipolar panel value (the Macro's BIPOLAR atom,
  `HEi.tY1()`) and its label from its name panel value (`HEi.kAg()`) when it
  attaches or loads. The writer now sets those sources. Live gate
  (`scratch/recreate/probe_persist3.py`): three Macros (polarity staged before
  insertion, set after insertion, left unipolar) and an LFO, renamed,
  relabelled and mapped, then `save_copy` and reopen: user names, numbered
  titles, labels, polarity, values and mapping amounts all identical.
- A Sampler's player settings are stored fields, not parameters, and the
  defaults changed across versions: the owner's Samplers hold repitch digital
  varirate off, 64 grains, onset threshold 0 and latch initial rate off, a
  fresh 6.1.5 Sampler on/256/2/on. The reader reports them (`model_fields`) and
  the reconstructor writes those that differ from the fresh device. Live gate
  (`probe_model_fields2.py`, scratch): playback mode Spectral and playhead
  freeze written, then save_copy + reopen identical; `use_select_fade` and
  `max_zones_playing_in_parallel` have no public setter and are reported as
  skipped.
- A mapping target two or more chains deep is addressed by the owner's full
  `chain_path` (container device and chain component per level, from the
  track's own chain); `device.modulator_bind {target_chain, target_device}`
  resolves it. A single chain index was ambiguous below the first level.
- Rebuilds of three more projects (2026-10-21/22) found: clip launch settings
  were never written (every clip kept a new clip's; 708 arranger clips of one
  project launch FROM_START); an audio import titles its clip after the file,
  so empty names must be written too; a launcher clip's loop can be longer
  than the clip; audio events' formant envelope resolution was not written.
  Live gate (`probe_r23.py`, scratch): arranger and launcher launch settings,
  a 32-beat launcher clip looping 64, an empty audio clip name, formant
  resolution 8 and fader 2.0 written, then save + reopen identical.
  A Polymer's parameters live in sub-modules (`MODULES/<i>/CONTENTS/CUTOFF`)
  and a mapping stores its target relative to the module, so the reader's
  `target_parameter` (`CUTOFF`) was ambiguous on the device and the bind
  failed; the reader now reports the device-relative path when its walk up
  from the target atom reaches the located owner device (verified on a fresh
  dump, and a rebuild binds all 5 Polymer mappings of that project).
  A bind onto a plugin parameter without the plugin loaded threw a
  NullPointerException (model walks queued null fields in an `ArrayDeque`);
  it now fails with "modulation target not found". A mapping onto the
  modulator's own device reports `target_owner.self: true`, and for a device
  inside a chain its `chain` and `chain_path` (before, the top-level device at
  the same index); verified by an engine-on rebuild of nested Infiltrators.
- `project.save_copy` of a large project stopped its audio engine in one run
  (all plugins deactivated, `document.activate_audio` never became ready);
  render from a fresh host on the saved copy instead.
- Offline export of the full 191-track project (and of its original) crashes
  `BitwigAudioEngine` at random when the bounce starts: exception 0xC0000409
  (BEX64) at one fixed offset inside the engine itself, never in a plugin
  module. Subsets of the project render; the same subset can crash or pass, so
  it is a race, not one culprit (bisection by track converged on an audio
  track that renders alone).

Automation clips (Bitwig's "Insert Automation Clip"):

- A lane (`W2C.oLP()`, a `jmN`) holds its own points (`ExU()`, an `Olb`) and
  automation clips (`UFD`, an `xh3` clip): each has a point lane (`MnS()`) in
  clip content time, a loop region (`y2x()`), loop switch (`Nsg`/`KF3`) and
  play start (`NFl().VtE()` / `CJq`). The composite read interleaves clip
  points with the lane's, so the reader now adds `base_points` and `clips`
  (start, end, loop, loop_start, loop_duration, play_start_offset, points) to
  lanes that have clips, and `model_interpolation`/`curvature` to real points
  (the exporter's `interpolation` stays linear).
- `automation.write_clips {target, kind, clips}` writes them after the lane's
  own points (`automation.write_target`), the way the UI does it:
  `jmN.IYM(start, length)` makes the clip, its point lane is refilled
  (`GUX`/`MJZ`/`NME`/`xmH` points), loop and play start are set, then
  `jmN.IYM(XEQ)` inserts it. When the lane has no point at a clip's end, Bitwig
  adds one there with the current value (the lane needs an output value).
