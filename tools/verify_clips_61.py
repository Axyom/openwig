"""Live verification of the arrangement / clip surface on Bitwig 6.1.

Scope: arranger clips, the notes inside them, and reading both back. Devices,
automation and rendering are deliberately not covered.

Requires Bitwig running with the OpenwigBridge controller enabled and
`openwig doctor` run once (it caches the per-build symbols and opens the clip scope).

Never wipes the project: it creates its own uniquely named tracks and leaves them in
place so the result can be inspected in the arranger. Run it in a throwaway project.

    python tools/verify_clips_61.py
"""
import sys
import time

from openwig import Note, Song
from openwig.bridge import BridgeClient
from openwig.read.notes import read_track

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print("[%s] %s  %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)


def main():
    # One connection for everything: the controller holds a single remote connection, so a
    # second client - even after the first disconnects - can race it and time out.
    b = BridgeClient(request_timeout=60)
    b.start()
    if not b.wait_connected(15):
        print("NOT CONNECTED - is Bitwig running with OpenwigBridge enabled?")
        return 1
    time.sleep(1.0)
    print("bitwig      :", (b.request("host.version") or {}).get("version"))
    print("symbol scope:", b.request("dev.symbols").get("source"))

    s = Song(tempo=120, bars=8, bridge=b, clean=False)

    # 1. one clip at a non-zero arranger position, with exact note data
    t = s.track("OW_VERIFY_A")
    notes = [Note(60, 0.0, 1.0, 0.8), Note(64, 1.0, 0.5, 0.6),
             Note(67, 1.5, 0.25, 1.0), Note(72, 3.0, 1.0, 0.5)]
    t.clip(notes, dur=4.0, start=4.0)
    time.sleep(1.2)
    clips = read_track(b, t.idx)["clips"]
    layout = [(c["clip_start"], c["clip_duration"], c["note_count"]) for c in clips]
    got = sorted((n["key"], round(n["start"], 3), round(n["duration"], 3),
                  round(n["velocity"], 3)) for c in clips for n in c["notes"])
    want = sorted((n.key, n.start, n.dur, n.vel) for n in notes)
    check("clip at start=4 len=4 with 4 notes, read back exactly",
          layout == [(4.0, 4.0, 4)] and got == want,
          "layout=%s notes_match=%s" % (layout, got == want))
    if got != want:
        print("    want", want)
        print("    got ", got)

    # 2. a multi-clip arrangement with gaps between the clips
    t2 = s.track("OW_VERIFY_B")
    segs = [(0.0, 4.0, [Note(36, i, 0.25) for i in range(4)]),
            (8.0, 2.0, [Note(38, 0.5, 0.5, 0.7)]),
            (12.0, 4.0, [Note(40 + i, i * 0.5, 0.5, 0.9) for i in range(8)])]
    t2.clips(segs)
    time.sleep(1.5)
    clips2 = sorted(read_track(b, t2.idx)["clips"], key=lambda c: c["clip_start"])
    layout2 = [(c["clip_start"], c["clip_duration"], c["note_count"]) for c in clips2]
    check("three clips with gaps at 0 / 8 / 12",
          layout2 == [(st, du, len(ns)) for st, du, ns in segs], "layout=%s" % layout2)
    notes_ok = len(clips2) == len(segs) and all(
        sorted((n["key"], round(n["start"], 3)) for n in c["notes"])
        == sorted((x.key, x.start) for x in ns)
        for c, (_, _, ns) in zip(clips2, segs))
    check("notes inside each of the three clips exact", notes_ok)

    # 3. a read is confined to the track asked for (the walk must not wander into siblings)
    t3 = s.track("OW_VERIFY_C")
    t3.clip([Note(48 + i, i * 0.5, 0.5, 0.7) for i in range(8)], dur=4.0, start=16.0)
    time.sleep(1.2)
    c3 = read_track(b, t3.idx)["clips"]
    check("clip at start=16 reads back at 16, and only this track's clip",
          [c["clip_start"] for c in c3] == [16.0] and len(c3) == 1,
          "starts=%s" % [c["clip_start"] for c in c3])

    b.stop()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n==== %d/%d passed ====" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
