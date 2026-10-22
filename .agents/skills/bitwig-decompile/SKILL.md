---
name: bitwig-decompile
description: >-
  Decompile and inspect Bitwig Studio 6.1.5 Java classes using CFR 0.152
  (scratch/tools/cfr.jar) instead of raw javap bytecode. Activate this skill
  whenever reverse engineering Bitwig classes, inspecting Api* or obfuscated
  model classes, or extending the Java bridge.
---

# Bitwig Java Decompilation (CFR 0.152)

Always use **CFR 0.152** (`scratch/tools/cfr.jar`) and existing decompiled sources in `scratch/decomp/` when inspecting Bitwig classes. Do **not** dump raw `javap -c` bytecode.

## 1. Check Existing Decompiled Files First

Before running CFR, check if the class is already decompiled under `scratch/decomp/` or `scratch/decomp_cfr/`:

- Unpackaged obfuscated classes: `scratch/decomp/<ClassName>.java`
- Packaged classes: `scratch/decomp/com/bitwig/.../<ClassName>.java`

> **Important**: Never run `Get-ChildItem -Path scratch -Recurse` unfiltered, because `scratch/bitwig-6.1.5-live` contains a full Bitwig installation.

## 2. Decompile Classes from the Original 6.1.5 JAR

Because the backup JAR (`scratch/bitwig-6.1.5-live/bin/bitwig.jar.openwig-original-6.1.5`, SHA-256 `d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49`) does not have a `.jar` extension, pass it via `--extraclasspath` followed by one or more fully qualified class names:

```powershell
java -jar scratch\tools\cfr.jar --extraclasspath scratch\bitwig-6.1.5-live\bin\bitwig.jar.openwig-original-6.1.5 <fully.qualified.ClassName> [more.ClassNames...] --outputdir scratch\decomp
```

### Examples

Decompile `ApiProject` and `ApiDawProjectExporter`:

```powershell
java -jar scratch\tools\cfr.jar --extraclasspath scratch\bitwig-6.1.5-live\bin\bitwig.jar.openwig-original-6.1.5 com.bitwig.flt.document.api.document.ApiProject com.bitwig.flt.document.api.document.ApiDawProjectExporter --outputdir scratch\decomp
```

Decompile an unpackaged obfuscated class (e.g. `roo` or `PNT`):

```powershell
java -jar scratch\tools\cfr.jar --extraclasspath scratch\bitwig-6.1.5-live\bin\bitwig.jar.openwig-original-6.1.5 roo PNT --outputdir scratch\decomp
```

## 3. Finding Class Names in the JAR

To search for classes in a package inside the backup JAR without extracting it:

```powershell
python -c "import zipfile; zf = zipfile.ZipFile('scratch/bitwig-6.1.5-live/bin/bitwig.jar.openwig-original-6.1.5'); print([n[:-6].replace('/', '.') for n in zf.namelist() if n.startswith('com/bitwig/flt/document/api/') and n.endswith('.class') and '$' not in n])"
```

## 4. Safety & Hygiene

- Keep all decompiled `.java` files inside `scratch/decomp/` (gitignored); never commit decompiled Bitwig sources.
- Record verified exact-build method signatures in `java_bridge/READER_SIGNATURES.md` and `java_bridge/README.md`.
