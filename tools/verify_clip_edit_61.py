"""Live verification of HEADLESS clip editing (Bitwig 6.1), through the public API.

Bitwig's cursor-clip calls (transpose_cursor / quantize_cursor / step_attr) only ever
touch the clip SELECTED in the arranger, so they do nothing when nothing is selected.
The methods exercised here address a clip by INDEX instead - the controller resolves it
from the document graph - so they work with no selection at all:

    t.clips_info() / t.transpose_clip() / t.resize_clip()
    t.add_notes()  / t.move_clip()      / t.rename_clip()

Every step is verified by reading the document back (openwig.read.notes), not by trusting
a return value.

Requires Bitwig running with OpenwigBridge enabled and `openwig doctor` run once (it
validates the clip paths for the build and opens the clip scope). Creates its own track
and leaves it in place - run it in a throwaway project.

    python tools/verify_clip_edit_61.py
"""
import json
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
    # One connection throughout: the controller holds a single remote connection, and a
    # second client - even after the first disconnects - can race it.
    b = BridgeClient(request_timeout=120)
    b.start()
    if not b.wait_connected(15):
        print("NOT CONNECTED - is Bitwig running with OpenwigBridge enabled?")
        return 1
    time.sleep(1.0)

    s = Song(tempo=120, bars=8, bridge=b, clean=False)
    t = s.track("OW_EDIT_VERIFY")
    t.clip([Note(60, 0.0, 1.0, 0.8), Note(64, 2.0, 0.5, 0.6)], dur=4.0, start=4.0)
    time.sleep(1.2)

    def state():
        """(start, length, keys, name) per clip, read from the document."""
        clips = read_track(b, t.idx)["clips"]
        return [(c["clip_start"], c["clip_duration"],
                 sorted(n["key"] for n in c["notes"]), c["name"]) for c in clips]

    start = state()
    print("clip as created:", json.dumps(start))
    if not start:
        print("no clip found - cannot verify editing")
        return 1

    info = t.clips_info()
    check("clips_info lists the clip (index, start, length)",
          len(info) == 1 and info[0]["start"] == 4.0 and info[0]["duration"] == 4.0,
          json.dumps(info))

    t.transpose_clip(0, 12)
    time.sleep(1.3)
    after = state()
    check("transpose_clip(+12)",
          bool(after) and after[0][2] == [k + 12 for k in start[0][2]],
          "keys %s -> %s" % (start[0][2], after[0][2] if after else None))

    prev = after
    t.resize_clip(0, end=6.0)
    time.sleep(1.3)
    after = state()
    check("resize_clip(end=6.0) => length 2",
          bool(after) and after[0][1] == 2.0,
          "length %s -> %s" % (prev[0][1], after[0][1] if after else None))

    prev = after
    t.add_notes(0, [Note(55, 0.5, dur=0.5, vel=0.9)])
    time.sleep(1.3)
    after = state()
    check("add_notes into the existing clip",
          bool(after) and len(after[0][2]) == len(prev[0][2]) + 1,
          "keys %s -> %s" % (prev[0][2], after[0][2] if after else None))

    prev = after
    t.move_clip(0, 8.0)
    time.sleep(1.3)
    after = state()
    check("move_clip(4 -> 8)", bool(after) and after[0][0] == 8.0,
          "start %s -> %s" % (prev[0][0], after[0][0] if after else None))

    t.rename_clip(0, "OW_RENAMED")
    time.sleep(1.3)
    after = state()
    check("rename_clip", bool(after) and after[0][3] == "OW_RENAMED",
          "name=%r" % (after[0][3] if after else None))

    info = t.clips_info()
    check("clips_info reflects the edits",
          len(info) == 1 and info[0]["start"] == 8.0 and info[0]["name"] == "OW_RENAMED",
          json.dumps(info))

    b.stop()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n==== %d/%d passed ====" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
