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
