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
tracks, and master devices and reports each count.
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
Song API parity test. The original Program Files installation was not patched.
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
Solo and arm are not exposed by the document API used here; they need the
obfuscated layer and are still missing.

Arranger automation passed live checks on a disposable FM-4 track: volume and
pan normalized readback, native `OUTPUT` parameter automation, curvature 0.4,
hold interpolation, lane replacement, and invalid-input rejection without
changing the existing lane. Tempo automation also passed point readback.
Repeat using `$env:PYTHONPATH="src"; python tests\live_java_automation.py` on a
disposable project with no existing tempo automation. Clearing a lane removes
its time-varying automation; Bitwig can retain one constant fallback point.

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
supported; enum writes have not yet been verified live.

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

Transport playback, stopping, position, and loop readback passed live checks;
play/stop state can take about 0.2 seconds to settle. Audible rendering live
verification remains pending. Repeat it in an
open disposable project using `$env:PYTHONPATH="src"; python tests\live_java_render.py`.
That check creates and deletes one temporary FM-4 track, checks playback and
loop readback, and writes `scratch/java-render-smoke.wav`. It does not clear or
save the project. Use an empty disposable project so existing tracks cannot
produce a false positive for the non-silence check.

Launcher clips are outside the current migration scope. `JavaSong.clear()` clears the active project of
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

Finish audible rendering verification, then port remaining track properties and run
the existing Python song examples against
the patched application. The main Song API still targets the controller on
port 7777 until those operations pass live tests.
