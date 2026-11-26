# Project: OpenWig Bridge (Patchbay mod)

Starts OpenWig's in-process JSON-RPC server on `127.0.0.1:7778` when Bitwig
starts, so the `openwig` Python SDK (`JavaSong`) can read and edit the open
project. Port of the ASM-patched jar in `../java_bridge` (Bitwig 6.1.5) to a
Patchbay (BML) mod for Bitwig **6.1.7 on Windows x64 only**: it is built
against 6.1.7's names and no map pack translates from that build.

Status: builds, passes Patchbay's offline check (`PREPARE_OK`, Bitwig 6.1.7,
1 hook, 10 opened members) and runs in Bitwig 6.1.7 launched through Patchbay
(2026-11-26, see "Live check").

## What the patched jar changed (bml-modkit jar-diff, 6.1.5 stock vs patched)

```
ADDED_CLASS OpenwigDocument, OpenwigTransport, com/bitwig/ramona/core/OpenwigUndo,
            com/openwig/bridge/Bootstrap, com/openwig/bridge/DirectAccess
CLASS com/bitwig/flt/app/BitwigStudioMain
  CHANGED_METHOD main([Ljava/lang/String;)V  -> ENTRY   (inserted: Bootstrap.start())
CLASS com/bitwig/flt/document/core/master/oZU
  CHANGED_METHOD <clinit>()V  -> REWRITE                (integrity-check redirect)
JAR_DIFF_DONE added_classes=5 hooks=1 changed_classes=2 needs_review=1
```

How each becomes part of the mod:

| jar change | in this mod |
|---|---|
| `BitwigStudioMain.main` ENTRY | `OpenwigHooks.startBridge`, one `@Hook(at = ENTRY)` |
| `oZU.<clinit>` REWRITE | dropped: Bitwig's engine hashes chunks of `bitwig.jar` on disk, and Patchbay never modifies that file, so the check sees the stock jar without help (to confirm in Bitwig) |
| added classes | `src/io/github/axyom/openwig/`; `OpenwigUndo` leaves `com.bitwig.ramona.core` (Patchbay refuses classes in Bitwig's packages) and reaches the members it needs through `opens` |

## Porting from 6.1.5 names to 6.1.7

Bitwig renames every obfuscated class and member in each release, and the
same short name is reused by hundreds of unrelated classes. The 6.1.5 bridge
reached about 600 of them through reflection (`getMethod("IYM")`,
`classForName("eY1")`, proxies dispatching on `"IYM"`), which only works on
the build it was written for. The port rewrites all of it as plain Java
against `bitwig.obf.*`, so `javac` and Patchbay's linkage check verify every
name against 6.1.7.

1. **Name map.** FabricMC Matcher's CLI matched the two jars
   (`porting/run_matcher.sh`; 25,958 of 31,665 classes, 387k methods). The 425
   names the old code referenced directly are reviewed in
   `porting/names-6.1.5-to-6.1.7.tsv`, each with its evidence (unique string,
   unique signature, agreeing callers, identical body).
2. **Receivers.** A reflective site names a member but not its class.
   `porting/sites.py` lists every site; the receiver came from the code around
   it, the readable `Api*` wrapper constructors (`ApiPluginDevice(Mv)` and so
   on), and CFR of the 6.1.5 classes. `porting/lookup.py <class> <member>`
   then gives the 6.1.7 name.
3. **Overloads.** Obfuscated classes often hold two methods with the same
   name and parameters but different return types. Each converted cluster was
   checked in the compiled bytecode (`javap -c`) to call the intended one.
4. **Enums** are matched by their stored names (`"DECIBEL"`, `"POST_FADER"`),
   which are readable and identical in both builds, instead of by obfuscated
   field names.

For the next Bitwig version: rerun Matcher, then either repeat step 2 for
changed names or generate a Patchbay map pack from Matcher's output so
Patchbay translates this mod without source changes.

### Reflection that remains, and why

| where | why it stays |
|---|---|
| `deviceScope`: a `java.lang.reflect.Proxy` of `Track` | wraps the public extension-API interface (no Bitwig-internal names) to answer `deviceChain()` with a nested chain and forward every other method |
| `describeGetters` and `debug.atom_methods` | debugging aids that list whatever getters/methods an object has; no names are written in the code |
| `debug.atom_invoke` | invokes a method the caller names, by design |

### 6.1.5 behaviour that changed in the port

| what | 6.1.5 bridge | port |
|---|---|---|
| Convolution impulse reads and `device.load_impulse` | looked up a class name that does not exist in 6.1.5 (`device.MM`; the class is `MM` in the default package), so reads found no impulses and loading failed | uses the impulse component class (`Bsy` in 6.1.7) |
| automation clip loop start/length | named a wrong class for ApiClip's rounding helpers (`anb`, not `anB`), so rounding was skipped | rounds through the same helpers ApiClip uses (`aoP`) |
| `model_interpolation` of automation points | compared the mode's name to an obfuscated field name, so it always said `linear` | reports `hold` for hold points |
| sample `loop_mode` | searched for a public enum getter whose type name starts with `by`; none exists, so it was never read or written | not read or written (same result, without the search) |

These follow what the 6.1.5 code meant to do; none is verified live yet.

## Live check (2026-11-26, Bitwig 6.1.7 through Patchbay, only this mod loaded)

On the unsaved startup project ("New 1"), never saved:

- The log shows `[openwig] ... bridge starting on 127.0.0.1:7778`; the port
  answers, `host.version` is 6.1.7, no exceptions from the mod in the log.
- 44 operations passed: document info/list, full project dump, tempo,
  transport snapshot/position/loop/play/stop, beats to seconds, instrument
  track with Polysynth, device read/parameters/rename/expand, note clip,
  clip copy/split/update, volume automation write and readback, track
  volume/pan/mute/solo/comment/color, modulator catalog/add/read/bind/unbind/
  delete, launcher notes/settings/launch/stop/return to arranger, effect and
  group tracks, scene insert/update/move/delete, cue marker, metadata,
  insert/delete/duplicate time, audio status, offline render of 4 beats.
- Not checked: undo of an edit from Bitwig's UI, and that no integrity-check
  (random pulse) melody plays (the project's audio engine was off). Not run:
  plugin state, Grid, layers, sampler, impulses, CLAP/VST2 insertion, audio
  events, DAWproject import/export, project open/close/save-copy.

## Building

See `docs/sdk/building.md` in the Patchbay install. JDK 25 (the kit is Java 25
bytecode), `javac --release 21`, compile jar from the 6.1.7 `bitwig.jar`.
`python build.py` runs steps 1 to 8 into `scratch/patchbay-out/openwig`;
`python build.py --check` adds `loader\prepare-mod.cmd` on that folder.
