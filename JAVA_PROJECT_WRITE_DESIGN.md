# Native project writing

The owner authorized full-project writing on 2026-10-18. This extends the
reader-only scope of `READER_PARITY_DESIGN.md`; corpus collection remains separate.

## Format and operations

`JavaSong.save_project(path)` writes a new `.bwproject` plus collected media.
On the document event queue, it serializes the selected live document into bytes,
deserializes a detached copy, initializes its file index, relocates that copy's
media references, and writes the native file. Native plugin-state blobs are
attached using Bitwig's own resource writer. The source document is not saved or
renamed. Existing destination files are refused, and partial output is removed
on failure. A `.bitwig-project` marker allows normal opening without a folder
conversion prompt. Missing non-package media/state aborts the operation; missing
licensed package resources remain package references.

`JavaSong.export_project(path)` captures that native directory into a ZIP with
`manifest.json` (`format: openwig-project-1`, `bitwig_version: 6.1.5`, project
filename, and SHA-256/size for every resource). `write_project(archive, directory)`
validates the archive and writes a fresh directory without changing the open
project. It also works offline via `openwig.project_archive.write_project`.
Open the returned `.bwproject` in Bitwig 6.1.5. No existing directory is replaced.
Duplicate/unsafe resource paths, corrupt hashes, incomplete inventories, and
unsupported archive versions are rejected. Failed materialization removes its
own staging/output directories.

`projects()`, `project_info()`, and `select_project(path)` identify and select
already open projects. Both indexed and path-based operations now target the
selected document instead of silently using the first project tab.

The archive preserves opaque native data rather than reconstructing it from the
partial JSON reader schema. A DAWproject experiment restored counts but lost
native automation, one clip, and audio/loop properties on the full project; that
approach was removed. `import_dawproject` remains a limited interchange operation.
This is native capture/restore and saving SDK-authored edits, not arbitrary
JSON-to-project synthesis or complete field-by-field editing parity.

## Verification

`tests/live_java_recreate.py` provides capture, offline restore, and reopened-file
audit phases. It requires the source path and expected track count, selects that
exact open project, and waits for three identical reader snapshots. Live dumps
are compared recursively. Relocated audio paths are replaced with content hashes;
explicitly unavailable plugin parameter caches are reported separately, and
embedded plugin-state bytes are compared with the source cache hashes. No other
field is excluded. Artifacts stay under ignored `scratch/`.

The full-project gate uses the released project read-only, never saves it, and
reopens only the newly written native copy. It checks 107 tracks (17 groups),
978 recursive devices (857 native), 2831 arranger clips, 2527 notes, 2898 audio
events, 10 scenes, and 10 markers, plus all reader-visible values and automation.
The archive collects 81 media files and 120 unique embedded plugin-state blobs.
The original project file hash is checked before and after the cycle.

Installed third-party plugins and licensed Bitwig packages are prerequisites.
Reader-hidden Grid topology and filled launcher slots are preserved by native
serialization but have not received independent field-level or audible-render
validation. This does not establish a complete JSON schema or full Song API parity.


Final live result (2026-10-18): `scratch/native-project-gate-settled/` contains
zero source-stability differences and zero restored differences under the rules
above. The audit hashed 132 source/restored audio paths, verified all 120 unique
plugin-state blobs, and reported 30 unavailable cache pairs separately. The
324255449-byte archive contains 83 resources (native document, folder marker,
and 81 media files). The source file retained SHA-256
`3cef520796171bc0ac4088fc89c142ae04f04711215c3bb77b648a23fa5b17a8`.
105 unit tests, the offline Java bridge build/JAR/protocol smoke, and controller
JavaScript syntax checks passed.
