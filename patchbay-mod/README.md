# Project: OpenWig Bridge (Patchbay mod)

Starts OpenWig's in-process JSON-RPC server on `127.0.0.1:7778` when Bitwig
starts, so the `openwig` Python SDK (`JavaSong`) can read and edit the open
project. Port of the ASM-patched jar in `../java_bridge` (Bitwig 6.1.5) to a
Patchbay (BML) mod for Bitwig **6.1.7 on Windows x64 only**: it is built
against 6.1.7's names and no map pack translates from that build.

Status: work in progress on branch `patchbay-port`. Not built yet.

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

## Building

See `docs/sdk/building.md` in the Patchbay install. JDK 25 (the kit is Java 25
bytecode), `javac --release 21`, compile jar from the 6.1.7 `bitwig.jar`.
