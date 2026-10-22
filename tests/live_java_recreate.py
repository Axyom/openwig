"""Manual full-project capture/restore audit; artifacts stay in ignored scratch.

Capture leaves the source document unchanged. Restore materializes an archive
offline. Open the returned native file in Bitwig before running audit.
"""
import argparse
import json
from pathlib import Path
import time
from openwig.bridge import BridgeClient
from openwig.java_song import JavaSong


def wait_project():
    b = BridgeClient(port=7778)
    b.start()
    try:
        assert b.wait_connected(40), 'Java bridge did not connect'
        deadline = time.monotonic() + 60
        while not b.request('document.status')['active']:
            assert time.monotonic() < deadline, 'Project did not open'
            time.sleep(.25)
    finally:
        b.stop()
    time.sleep(12)


def tracks(dump):
    def walk(items):
        for t in items:
            yield t
            yield from walk(t.get('children', []))
    yield from walk(dump.get('main_tracks', []))
    yield from walk(dump.get('effect_tracks', []))
    yield dump['master']


def counts(dump):
    ts = list(tracks(dump))
    def devices(ds):
        for d in ds:
            yield d
            for chain in d.get('chains', []):
                yield from devices(chain.get('devices', []))
    ds = [d for t in ts for d in devices(t.get('devices', []))]
    return dict(tracks=len(ts), groups=sum(t.get('is_group', False) for t in ts),
                devices=len(ds), native=sum(d.get('kind') == 'native' for d in ds),
                clips=sum(len(t.get('clips', [])) for t in ts),
                notes=sum(len(c.get('notes', [])) for t in ts for c in t.get('clips', [])),
                audio_events=sum(len(c.get('audio_events', [])) for t in ts for c in t.get('clips', [])),
                scenes=len(dump.get('scenes', [])), markers=len(dump.get('cue_markers', [])))


def differences(a, b, path=''):
    if type(a) is not type(b) and not (isinstance(a, (int,float)) and isinstance(b, (int,float))):
        yield dict(path=path, before=a, after=b)
    elif isinstance(a, dict):
        for k in sorted(a.keys() | b.keys()):
            if k not in a or k not in b:
                yield dict(path=path+'/'+k, before=a.get(k), after=b.get(k))
            else:
                yield from differences(a[k], b[k], path+'/'+k)
    elif isinstance(a, list):
        if len(a) != len(b):
            yield dict(path=path+'/length', before=len(a), after=len(b))
        for i, (x,y) in enumerate(zip(a,b)):
            yield from differences(x,y,path+f'/{i}')
    elif a != b:
        if isinstance(a, (int,float)) and isinstance(b, (int,float)) and abs(a-b)<1e-6:
            return
        yield dict(path=path, before=a, after=b)


def audit(before, after):
    """Compare reader-visible data; account only for hashes and explicit caches."""
    from copy import deepcopy
    from hashlib import file_digest
    before, after = deepcopy(before), deepcopy(after)
    caches = []
    media = {}

    def digest(path):
        if path not in media:
            with open(path, "rb") as stream:
                media[path] = file_digest(stream, "sha256").hexdigest()
        return media[path]

    def normalize(a, b, path=""):
        if isinstance(a, dict) and isinstance(b, dict):
            if "parameters_available" in a and "parameters_available" in b:
                if not a["parameters_available"] or not b["parameters_available"]:
                    caches.append(dict(path=path, before=a["parameters_available"],
                                       after=b["parameters_available"]))
                    # Runtime plugin caches cannot prove opaque plugin-state fidelity.
                    # Their native embedded blobs are checked separately by hash.
                    for key in ("parameters", "parameters_available", "parameters_status"):
                        a.pop(key, None); b.pop(key, None)
            for key in a.keys() & b.keys():
                if key == "file" and "/audio_events/" in path:
                    a[key], b[key] = digest(a[key]), digest(b[key])
                else:
                    normalize(a[key], b[key], path + "/" + key)
        elif isinstance(a, list) and isinstance(b, list):
            for i, (x, y) in enumerate(zip(a, b)):
                normalize(x, y, path + f"/{i}")

    normalize(before, after)
    return dict(before=counts(before), after=counts(after),
                differences=list(differences(before, after)),
                unavailable_plugin_caches=caches, media_paths_hashed=len(media))


def plugin_states(dump):
    import hashlib
    states = {}
    def walk(value):
        if isinstance(value, dict):
            if value.get("state_file"):
                file = Path(value["state_file"])
                with file.open("rb") as stream:
                    states["plugin-states/" + file.name] = hashlib.file_digest(stream, "sha256").hexdigest()
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(dump)
    return states


def embedded_states(archive):
    import hashlib
    import io
    import zipfile
    with zipfile.ZipFile(archive) as bundle:
        manifest = json.loads(bundle.read("manifest.json"))
        with zipfile.ZipFile(io.BytesIO(bundle.read(manifest["project"]))) as native:
            return {name: hashlib.sha256(native.read(name)).hexdigest()
                    for name in native.namelist() if name.startswith("plugin-states/")}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("phase", choices=["capture", "restore", "audit"])
    p.add_argument("--directory", type=Path, default=Path("scratch/native-project-gate"))
    p.add_argument("--project", type=Path, help="already open native project to select")
    p.add_argument("--expected-tracks", type=int, required=True)
    args = p.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / "project.openwig.zip"
    destination = directory / "restored-project"
    if args.phase == "restore":
        from openwig.project_archive import write_project
        print(write_project(archive, destination), flush=True)
        return
    if args.project is None:
        p.error("capture and audit require --project to select an already open file")
    wait_project()
    with JavaSong() as song:
        deadline = time.monotonic() + 120
        while not any(Path(item["path"]) == args.project.resolve() for item in song.projects()):
            assert time.monotonic() < deadline, "requested project did not open"
            time.sleep(1)
        song.select_project(args.project)
        before = song.dump()
        # Plugin descriptors may finish loading after the document appears.
        # Require three equal full reads before using a snapshot as evidence.
        stable = 0
        deadline = time.monotonic() + 120
        while stable < 2:
            time.sleep(5)
            current = song.dump()
            stable = stable + 1 if current == before else 0
            before = current
            assert time.monotonic() < deadline, "project read did not settle"
        assert counts(before)["tracks"] == args.expected_tracks, counts(before)
        if args.phase == "capture":
            (directory / "source.json").write_text(json.dumps(before, indent=2))
            states = plugin_states(before)
            (directory / "plugin-state-hashes.json").write_text(json.dumps(states, indent=2))
            print(song.export_project(archive), flush=True)
            assert embedded_states(archive) == states, "embedded plugin state hash mismatch"
            after = song.dump()
            (directory / "source-after.json").write_text(json.dumps(after, indent=2))
            result = audit(before, after)
            (directory / "source-stability.json").write_text(json.dumps(result, indent=2))
            assert not result["differences"], result["differences"][:10]
        else:
            (directory / "restored.json").write_text(json.dumps(before, indent=2))
            source = json.loads((directory / "source.json").read_text())
            result = audit(source, before)
            expected_states = json.loads((directory / "plugin-state-hashes.json").read_text())
            assert embedded_states(archive) == expected_states
            result["plugin_states_verified"] = len(expected_states)
            (directory / "comparison.json").write_text(json.dumps(result, indent=2))
            print(json.dumps({k: v if k != "unavailable_plugin_caches" else len(v) for k, v in result.items()}, indent=2)[:6000], flush=True)
            assert not result["differences"], result["differences"][:10]


if __name__ == "__main__":
    main()
