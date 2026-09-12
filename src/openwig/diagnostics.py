"""openwig.diagnostics - live self-test of the reflection paths (the "resolver").

The bridge reaches into Bitwig's obfuscated internals to write arranger automation,
create arranger clips, and read the document graph. Those internal class/method names are
stable for a given Bitwig build but get re-obfuscated each release, so they can move
between versions. `run_selftest` verifies, on a throwaway track, that each path still works
on the LIVE build, resolves the descriptor-reader names, and (controller side) writes a
symbol cache the bridge loads at init. `openwig doctor` prints the report.

The probe creates a temporary instrument track, writes to it, verifies the writes via a
descriptor read, then deletes it. It never modifies your existing tracks. It fails safe: a
broken path is reported, not silently ignored.
"""
from __future__ import annotations

import struct
import time
import wave
from pathlib import Path

from openwig.bridge import BridgeClient, BridgeError
from openwig.paths import data_dir as _data_dir

PROBE_TRACK = "__openwig_probe__"


def _write_silent_wav(path: Path, seconds: float = 0.1):
    path.parent.mkdir(parents=True, exist_ok=True)
    n = int(44100 * seconds)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
        w.writeframes(struct.pack("<" + "h" * n, *([0] * n)))


def _validate_audio(b, idx):
    """Validate (and if needed re-resolve) arranger audio-clip insert on track `idx`.

    The dispatch / ZjS / mode resolve structurally controller-side; only the track-as-HrV
    accessor needs execution validation (audio insert is async, so this is orchestrated here:
    insert a uniquely-named test wav via a candidate accessor, wait for the decode, and check
    the descriptor walk surfaces the file name). Returns {ok, detail, hrv}.
    """
    res = b.request("resolver.audio_candidates")
    if res.get("error"):
        return {"ok": False, "detail": res["error"]}
    cands = res.get("hrv_candidates") or []
    data = _data_dir()

    def walk_text():
        b.request_op("obj.walk", {"max_depth": 16, "max_nodes": 9000}, timeout=12.0)
        return b.request("obj.walk_result").get("json") or ""

    # try the current default (None override) first, then each candidate
    for hrv in [None] + cands:
        marker = "owtest_" + (hrv or "default")
        wavp = data / (marker + ".wav")
        try:
            _write_silent_wav(wavp)
        except OSError as exc:
            return {"ok": False, "detail": f"cannot write test wav: {exc}"}
        params = {"track": idx, "path": str(wavp)}
        if hrv:
            params["hrv"] = hrv
        # gate-exempt alias: doctor validates this path before symbols are validated, so it
        # cannot use the (gated) public track.insert_audio_clip op.
        b.request("resolver.audio_probe_insert", params)
        time.sleep(2.5)  # audio decode is async/off-thread
        if marker in walk_text():
            if hrv:
                b.request("resolver.set_audio_hrv", {"hrv": hrv})
            return {"ok": True, "hrv": hrv or "(default)", "detail": "inserted + read back"}
    return {"ok": False, "detail": "no HrV accessor produced a clip"}


def _occupied(b):
    """Set of main-track indices currently occupied (by name; the exists flag is flaky)."""
    snap = b.request("state.snapshot")
    return {t.get("index") for t in snap.get("tracks", []) if t.get("name")}


def _delete_index(b, idx):
    try:
        b.request("track.delete", {"index": idx})
    except BridgeError:
        pass


def _create_probe_track(b, before, *, polls=8, name=PROBE_TRACK):
    """Create + select a throwaway instrument track; return its index, or None.

    The new track is identified by INDEX-DIFF against `before` (the occupied set the
    caller snapshotted via _occupied), never by name: the post-create rename can
    silently fail right after a controller reload. The caller must clean up with
    _cleanup_probe_tracks(b, before) in a finally. Shared with the live tests.
    """
    b.request("track.create", {"type": "instrument", "name": name})
    idx = None
    for _ in range(polls):
        time.sleep(0.25)
        new_indices = sorted(_occupied(b) - before)
        if new_indices:
            idx = new_indices[-1]
            break
    if idx is None:
        return None
    b.request("track.select", {"index": idx})
    time.sleep(0.3)
    return idx


def _cleanup_probe_tracks(b, before):
    """Delete every slot that appeared since `before` (covers a failed rename), highest
    index first so earlier indices stay valid. Guarded: cleanup must never mask the
    report or test result (an exception raised in a finally supersedes the in-flight
    return, so a bridge that died mid-probe would otherwise eat a useful partial report).
    """
    try:
        appeared = sorted(_occupied(b) - before, reverse=True)
    except Exception:  # noqa: BLE001
        return
    for idx in appeared:
        _delete_index(b, idx)


def run_selftest(b=None, *, timeout=90.0):
    """Run the resolver self-test against live Bitwig.

    Returns the report dict from ``resolver.probe`` augmented with a top-level
    ``connected`` flag (and ``classes`` / ``bitwig`` even when the round-trip can't run).
    Creates and deletes a temporary probe track; never modifies existing tracks. The probe
    track is identified by INDEX-DIFF (the new slot that appears), not by name, because the
    post-create rename can silently fail right after a controller reload.
    """
    own = b is None
    if own:
        b = BridgeClient()
        b.start()
    try:
        if not b.wait_connected(4.0):
            return {"connected": False}
        # class-load check is cheap and works even if the round-trip can't run
        classes = b.request("resolver.classes")
        base = {"connected": True,
                "classes": classes.get("classes"),
                "bitwig": classes.get("bitwig")}
        before = _occupied(b)
        try:
            idx = _create_probe_track(b, before)
            if idx is None:
                base["error"] = "probe track did not appear (cannot run round-trip)"
                return base
            # Pass the probe track's name as a reader sentinel. The written sentinels
            # (automation time / note start) only exist if those writes worked, but the
            # track name is in the document regardless - so the descriptor reader can be
            # resolved even on a build where the write paths are not yet verified. That is
            # what lets a re-obfuscated build bootstrap instead of failing everything.
            # Both the requested name and whatever the snapshot reports for that slot: the
            # post-create rename can silently fail, so the two can differ and only one of
            # them is actually in the document.
            snap_name = next((t.get("name") for t in b.request("state.snapshot").get("tracks", [])
                              if t.get("index") == idx), None)
            sentinels = [n for n in (PROBE_TRACK, snap_name) if n]
            # Two ops on purpose. On Bitwig 6.1 a write is not visible to the descriptor
            # reader inside the same document-edit task, so the sentinels can only be read
            # back - and the reader validated against them - in a LATER task. The write
            # phase inserts them; the verify phase resolves the reader and checks the
            # read-back. (On 6.0.x a single "all" pass also works.)
            b.request_op("resolver.probe",
                         {"phase": "write", "name_sentinel": sentinels}, timeout=timeout)
            time.sleep(0.5)
            b.request_op("resolver.probe",
                         {"phase": "verify", "name_sentinel": sentinels}, timeout=timeout)
            res = b.request("resolver.result")
            report = res.get("report") or dict(base)
            report["connected"] = True
            if res.get("error"):
                report["error"] = res["error"]
            # arranger audio-clip insert is validated separately (async; orchestrated here).
            # Without a verified reader the read-back can never match, so don't pay the
            # full per-candidate loop (wav + insert + 2.5s decode + 9000-node walk each)
            # just to print one more FAIL line.
            caps = report.get("capabilities") or {}
            if (caps.get("descriptor_read") or {}).get("ok"):
                try:
                    report["audio"] = _validate_audio(b, idx)
                except BridgeError as exc:
                    report["audio"] = {"ok": False, "detail": f"error ({exc})"}
            else:
                report["audio"] = {"ok": False,
                                   "detail": "skipped (descriptor read not verified; read-back impossible)"}
            return report
        finally:
            _cleanup_probe_tracks(b, before)
    finally:
        if own:
            b.stop()
