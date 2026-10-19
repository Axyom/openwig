"""Manual gate: native project open/close in a running Bitwig, never saving.

Needs the patched scratch Bitwig with --base open. Opens --second in a new tab,
edits it, closes it with discard, proves the file is byte-identical and the
edit is gone on reopen, then closes every tab and opens from zero tabs.
Optional --older opens a copy saved by an older Bitwig and records the
version backup Bitwig writes next to it. All paths must be disposable copies.
"""
import argparse
import hashlib
import json
import time
from pathlib import Path

from openwig import JavaSong
from openwig.bridge import BridgeClient, BridgeError
from live_java_launcher import wait_for_document


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def open_paths(song) -> list[Path]:
    return [Path(d["path"]).resolve() for d in song.projects()]


def track_names(dump) -> list[str]:
    return [t["name"] for t in dump["main_tracks"]]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base", type=Path, required=True)
    p.add_argument("--second", type=Path, required=True)
    p.add_argument("--older", type=Path)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    base, second = a.base.resolve(), a.second.resolve()
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    report = {}
    b = BridgeClient(port=7778); b.start()
    try:
        assert b.wait_connected(60)
        wait_for_document(b, base)
    finally:
        b.stop()
    with JavaSong() as s:
        before = digest(second)
        started = time.monotonic()
        s.open_project(second)
        report["open_seconds"] = round(time.monotonic() - started, 2)
        assert second in open_paths(s) and base in open_paths(s), open_paths(s)
        assert Path(s.project_info()["path"]).resolve() == second
        dump = s.dump()
        report["second_tracks"] = len(dump["main_tracks"])
        s.track("Openwig unsaved probe")
        assert "Openwig unsaved probe" in track_names(s.dump())

        again = s.b.request("document.open", {"path": str(second)})
        assert again.get("already_open") is True, again

        started = time.monotonic()
        s.close_project(second, discard_changes=True)
        report["close_seconds"] = round(time.monotonic() - started, 2)
        assert second not in open_paths(s), open_paths(s)
        assert digest(second) == before, "closing must not save"
        s.select_project(base)

        s.open_project(second)
        assert "Openwig unsaved probe" not in track_names(s.dump()), "discarded edit came back"
        s.close_project(second, discard_changes=True)

        for method, params in [
            ("document.close", {"path": str(second), "discard_changes": True}),
            ("document.close", {"path": str(base)}),
            ("document.open", {"path": str(second.with_name("missing.bwproject"))}),
            ("document.open", {"path": str(out / "report.json")}),
            ("document.job_status", {"job": "unknown"}),
        ]:
            try:
                s.b.request(method, params)
            except BridgeError:
                continue
            raise AssertionError(f"accepted invalid request {method} {params}")
        try:
            s.close_project(base)
        except ValueError:
            pass
        else:
            raise AssertionError("close without discard_changes must be refused")

        # From zero tabs: Bitwig shows its dashboard; opening must still work.
        s.close_project(base, discard_changes=True)
    deadline = time.monotonic() + 30
    b = BridgeClient(port=7778); b.start()
    try:
        assert b.wait_connected(30)
        while b.request("document.list")["documents"]:
            assert time.monotonic() < deadline, "tabs did not close"
            time.sleep(.25)
        job = b.request("document.open", {"path": str(base)})
        while job["state"] == "running":
            time.sleep(.25)
            job = b.request("document.job_status", {"job": job["job"]})
        assert job["state"] == "complete", job
        wait_for_document(b, base)
        report["reopened_from_zero_tabs"] = True
    finally:
        b.stop()
    if a.older:
        older = a.older.resolve()
        siblings = {p for p in older.parent.rglob("*") if p.is_file()}
        with JavaSong() as s:
            s.open_project(older)
            created = sorted(str(p.relative_to(older.parent)) for p in older.parent.rglob("*")
                             if p.is_file() and p not in siblings)
            report["older_version_backup_files"] = created
            s.close_project(older, discard_changes=True)
            s.select_project(base)
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
