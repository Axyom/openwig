# Project: OpenWig Bridge (Patchbay mod)

Starts OpenWig's in-process JSON-RPC server on `127.0.0.1:7778` when Bitwig
starts, so agents (through the MCP server below) can read and edit the open
project. Port of the ASM-patched jar in `java_bridge/` (Bitwig 6.1.5, at tag
`python-sdk-final`) to a
Patchbay (BML) mod for Bitwig **6.1.7 on Windows x64 only**: it is built
against 6.1.7's names and no map pack translates from that build.

Status: builds, passes Patchbay's offline check (`PREPARE_OK`, Bitwig 6.1.7,
1 hook, 10 opened members) and runs in Bitwig 6.1.7 launched through Patchbay
(2026-11-26, see "Live check").

## MCP server

With the mod loaded, Bitwig also serves MCP at `http://127.0.0.1:7779/mcp`
(Streamable HTTP, JSON responses, local connections only). Change the port with
the system property `openwig.mcp.port` or the environment variable
`OPENWIG_MCP_PORT`. The JSON-RPC bridge on port 7778 keeps working.

Tools: `bitwig_methods` (what can be done, by area), `bitwig_call` (run any of
the 146 bridge methods with its params), `bitwig_project` (project summary) and
`bitwig_transport` (playback state). Each call goes through the same dispatch
as port 7778, so every edit is one undo step in Bitwig.

Handwritten on the JDK's HTTP server and Bitwig's own org.json: the official
MCP Java SDK needs Reactor and its own Jackson version, while Bitwig already
ships another Jackson on the same class path.

Connect Claude Code:

```
claude mcp add --transport http openwig http://127.0.0.1:7779/mcp
```

Claude Desktop: Settings, Connectors, add a custom connector with the URL
`http://127.0.0.1:7779/mcp`.

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
| `oZU.<clinit>` REWRITE | dropped: Bitwig's engine hashes chunks of `bitwig.jar` on disk, and Patchbay never modifies that file, so the check sees the stock jar without help (no melody heard on 6.1.7, 2026-11-26) |
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

All runs used the unsaved startup project ("New 1") or a scratch copy of it
(`scratch/live617/copy`), opened and closed without saving. Scripts:
`scratch/live_617*.py` (not committed).

- The log shows `[openwig] ... bridge starting on 127.0.0.1:7778`; the port
  answers, `host.version` is 6.1.7, no exceptions from the mod in the log.
- **145 of the 146 methods ran and returned correct results**, read back
  where possible. Highlights beyond the basics: plugin parameters, plugin
  state capture and `.vstpreset` export, VST3 insertion with that state, CLAP
  insertion with a `.clap-preset` state and CLAP state capture, Grid
  add/move/connect/rename/delete, layers create/update/move/delete, bands,
  Drum Machine pads, Sampler sample loading, model fields, Convolution impulse
  loading, native and plugin sidechains, modulator move/parameters/rename,
  notes and audio events, launcher audio/copy/clear, automation targets and
  automation clips, track routing (destination, input source), DAWproject
  export and re-import, native save-copy, opening and closing a project,
  offline render, and every clear/delete operation.
- Not run: `debug.atom_invoke` (calls a method the caller names; diagnostic
  only). VST2 insertion was not exercised: the test project has no VST2
  plugin (the method itself passed with VST3 and CLAP).
- Failures seen during the runs were test-script or setup issues, not the
  port: the template's tracks are named like "Kick/Bass" and `/` is the
  track-path separator; job-status calls need the track address; the audio
  engine was off until `document.activate_audio`, so plugins first reported
  NOT_LOADED; a device's own track is refused as its sidechain source by
  Bitwig (the bridge then returns the unchanged source, as on 6.1.5).
- The owner heard no integrity-check (random pulse) melody with the mod
  loaded (2026-11-26). Still for the owner: undo of a bridge edit from
  Bitwig's UI.

## Building

See `docs/sdk/building.md` in the Patchbay install. JDK 25 (the kit is Java 25
bytecode), `javac --release 21`, compile jar from the 6.1.7 `bitwig.jar`.
`python mods/openwig/build.py` runs steps 1 to 8 into `scratch/patchbay-out/openwig`;
`python build.py --check` adds `loader\prepare-mod.cmd` on that folder.
