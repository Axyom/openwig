# API reference

## Note

`Note(key, start, dur=0.5, vel=1.0, channel=0)` - a named tuple for one note.

```python
from openwig import Note
Note(36, 0.0, dur=0.25)            # kick on beat 1
Note(60, 1.5, dur=0.5, vel=0.8)    # softer note at beat 1.5
```

---

## Song

```python
from openwig import Song
s = Song(tempo=128, bars=16, clean=True)
```

| Method | Description |
|---|---|
| `Song(tempo, bars, clean, check_version)` | Connect to Bitwig. `clean=True` wipes the project first. |
| `s.track(name, device, uuid, kind)` | Create an instrument track. Returns `Track`. |
| `s.audio_track(name)` | Create an audio track. Returns `Track`. |
| `s.fx_track(name, device)` | Create an effect/return track. Returns `Track`. |
| `s.master(chain, tune)` | Build the master FX chain, e.g. `["EQ+", "Compressor+", "Peak Limiter"]`. |
| `s.play(loop)` | Start transport. `loop=True` loops the arrangement. |
| `s.stop()` | Stop transport. |
| `s.render(path)` | Render to `.wav` via WASAPI loopback. Returns a dict: `{path, seconds, rate, channels, rms, silent}` (`silent`/`rms` confirm it made sound). |
| `s.clear()` | Delete all tracks and master FX. |
| `s.set_tempo(bpm)` | Change tempo live. |
| `s.automate_tempo(points)` | Tempo automation. `points`: `[(beat, bpm), ...]`. |
| `s.metronome(on)` | Toggle metronome. |
| `s.undo(n)` / `s.redo(n)` | Undo/redo N steps. |
| `s.panel(layout)` | Switch panel: `"ARRANGE"` / `"MIX"` / `"EDIT"` / `"PLAY"`. |
| `s.marker()` | Drop a cue marker at the current playhead (Bitwig auto-names it). |
| `s.scene_launch(index)` | Launch a scene. |
| `s.stop_all()` | Stop all clip slots. |
| `s.save()` | Save project in place. |
| `s.save_as_dialog()` | Open Save-As dialog. |
| `s.open_dialog()` | Open project picker dialog. |
| `s.new_project()` | Create a new empty project. |
| `s.verbose(on)` | Print every bridge call to stdout (debugging). |
| `s.close()` | Disconnect from the bridge. |

---

## Track

Most `Track` methods mutate and return `self`, so they chain
(`track.fx(...).clip(...)`). The query methods - `describe_clip`,
`remote_pages`, `routing_info`, `set_clip_prop` - return data instead, so call
them at the end of a chain, not in the middle.

### Channel strip

| Method | Description |
|---|---|
| `t.fader(level)` | Volume. `0.0` = silent, `1.0` = unity. |
| `t.pan(value)` | Pan. `-1.0` = full left, `0.0` = center, `1.0` = full right. |
| `t.mute(on)` | Mute. |
| `t.solo(on)` | Solo. |
| `t.arm(on)` | Record-arm (instrument/audio tracks only). |
| `t.monitor(mode)` | Monitor mode: `"OFF"` / `"AUTO"` / `"ON"`. |
| `t.color(r, g, b, a)` | Track color. All values `0.0..1.0`. |
| `t.send(send_index, value)` | Send level to an effect track. |
| `t.rename(name)` | Rename the track. |

### Clips

| Method | Description |
|---|---|
| `t.clip(notes, dur, start)` | One arranger clip spanning the song (or `dur` beats from `start`). |
| `t.clips(segments)` | Multiple arranger clips. `segments`: `[(start, dur, notes), ...]`. |
| `t.scene(slot, notes, dur, step_size)` | Launcher clip in slot `slot`. |
| `t.launch(slot)` | Launch launcher slot. |
| `t.sample(path, slot)` | Load audio into a launcher slot (audio tracks). |
| `t.audio_clip(path, start, duration)` | Drop a `.wav`/`.aiff` onto the arranger at `start` beats for `duration` beats (audio tracks; needs `openwig doctor` run once per build). |
| `t.audio_clips(segments)` | Several arranger audio clips. `segments`: `[(path, start, duration), ...]`. |
| `t.transpose_cursor(semitones)` | Transpose the selected clip. |
| `t.quantize_cursor(amount)` | Quantize the selected clip's notes (`0..1`). |
| `t.step_attr(x, key, attr, value)` | Set a per-note attribute on a step-grid clip. `attr`: `"velocity"` / `"chance"` / `"pan"` / `"timbre"` / `"pressure"` / `"duration"` / `"release"` / `"transpose"` / `"gain"`. |
| `t.describe_clip()` | List all property IDs on the selected clip (discovery). |
| `t.set_clip_prop(prop_id, value)` | Set a clip property by ID (discovered via `describe_clip`). |

### Editing existing clips

These address a clip by **index** in arranger order (see `t.clips_info()`), so they work on
any clip on the track. That is the difference from `transpose_cursor` / `quantize_cursor` /
`step_attr` above, which drive Bitwig's *cursor* clip and therefore only affect the clip
selected in the GUI - doing nothing when nothing is selected.

| Method | Description |
|---|---|
| `t.clips_info()` | This track's arranger clips: `[{index, start, duration, name}, ...]`. |
| `t.move_clip(index, start)` | Move the clip to `start` beats on the arranger. |
| `t.resize_clip(index, end)` | Resize by setting the clip's END position (absolute beats). |
| `t.transpose_clip(index, semitones)` | Transpose every note in the clip. |
| `t.rename_clip(index, name)` | Rename the clip. |
| `t.add_notes(index, notes)` | Add notes to the existing clip (starts are clip-relative). |
| `t.clip_cmd(index, name, args)` | Dispatch a raw clip command (escape hatch), e.g. `duplicate_content`. |

```python
t = s.track("BASS")
t.clip(notes, dur=4, start=4)

t.move_clip(0, 8.0)
t.resize_clip(0, end=10.0)
t.transpose_clip(0, 12)
t.rename_clip(0, "verse")
t.add_notes(0, [Note(55, 0.5, 0.5, 0.9)])
```

### Devices

| Method | Description |
|---|---|
| `t.fx(name, **remotes)` | Insert a factory device and tune remotes, e.g. `fx("Reverb", Mix=0.3)`. |
| `t.add_device(name)` | Insert a factory device by name. |
| `t.add_bitwig(uuid)` | Insert a Bitwig device by UUID. |
| `t.preset(path)` | Load a `.bwpreset` file (replaces the device chain). |
| `t.delete_device()` | Delete the currently-selected device. |
| `t.select_device(index)` | Select device at position `index` in the chain. |
| `t.remote_pages()` | List all remote control pages of the cursor device. |

### Automation and mix

| Method | Description |
|---|---|
| `t.automate(param, points, remote_index)` | Offline automation. `param`: `"volume"` / `"pan"` / `"remote"`. `points`: `[(beat, value), ...]`. |

### Routing

| Method | Description |
|---|---|
| `t.routing_info()` | Read current routing state (read-only). |
