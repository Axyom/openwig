"""Live verification of NOTE editing inside existing clips (Bitwig 6.1).

Bitwig's step-grid calls (step_attr / quantize_cursor) act on the clip SELECTED in the
arranger, so they do nothing when nothing is selected. The methods here address a clip and
a note by INDEX, resolved from the document graph, and work with no selection:

    t.notes_info(clip)  /  t.set_note(clip, note, ...)  /  t.delete_note(clip, note)

The clip deliberately contains two notes on the SAME pitch, because deletion is built as
"wipe that pitch, re-insert the survivors" - so the second C3 surviving is the real test.

Every step is verified by reading the document back, and the final state is cross-checked
against openwig.read.notes (a different read path than the controller listing).

Requires Bitwig running with OpenwigBridge enabled and `openwig doctor` run once. Creates
its own track and leaves it in place - run it in a throwaway project.

    python tools/verify_note_edit_61.py
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
    b = BridgeClient(request_timeout=120)
    b.start()
    if not b.wait_connected(20):
        print("NOT CONNECTED - is Bitwig running with OpenwigBridge enabled?")
        return 1
    time.sleep(1.0)

    s = Song(tempo=120, bars=8, bridge=b, clean=False)
    t = s.track("OW_NOTE_VERIFY")
    t.clip([Note(60, 0.0, 1.0, 0.8),
            Note(64, 1.0, 0.5, 0.6),
            Note(60, 2.0, 0.5, 0.7)], dur=4.0, start=4.0)
    time.sleep(1.3)

    def notes():
        return [(n["note"], n["key"], n["start"], n["duration"], n["velocity"], n["muted"])
                for n in t.notes_info(0)]

    start = notes()
    print("notes as created:", json.dumps(start))
    check("notes_info lists the three notes with their values",
          [(n[1], n[2], n[3], n[4]) for n in start] ==
          [(60, 0.0, 1.0, 0.8), (64, 1.0, 0.5, 0.6), (60, 2.0, 0.5, 0.7)],
          json.dumps([(n[1], n[2], n[3], n[4]) for n in start]))

    t.set_note(0, 0, velocity=0.25)
    time.sleep(1.2)
    after = notes()
    check("set_note(velocity=0.25) on note 0",
          bool(after) and after[0][4] == 0.25,
          "velocity %s -> %s" % (start[0][4], after[0][4] if after else None))

    prev = after
    t.set_note(0, 0, duration=0.75)
    time.sleep(1.2)
    after = notes()
    check("set_note(duration=0.75) on note 0",
          bool(after) and after[0][3] == 0.75,
          "duration %s -> %s" % (prev[0][3], after[0][3] if after else None))

    t.set_note(0, 0, muted=True)
    time.sleep(1.2)
    after = notes()
    check("set_note(muted=True) on note 0",
          bool(after) and after[0][5] is True,
          "muted=%s" % (after[0][5] if after else None))

    # Moving a note re-orders the list (it is sorted by start, then pitch), which is why
    # the note index has to be re-read afterwards.
    t.set_note(0, 1, start=3.5)
    time.sleep(1.2)
    after = notes()
    moved = [n for n in after if n[1] == 64]
    check("set_note(start=3.5) moves the E3 note",
          len(moved) == 1 and moved[0][2] == 3.5,
          "E3 start -> %s; order now %s" % (moved[0][2] if moved else None,
                                            [n[1] for n in after]))

    # Delete one of the two C3 notes; the other must survive the wipe-and-reinsert.
    before_del = notes()
    c3 = [n for n in before_del if n[1] == 60]
    victim = c3[0][0]
    t.delete_note(0, victim)
    time.sleep(1.5)
    after = notes()
    c3_after = [n for n in after if n[1] == 60]
    check("delete_note removes one C3 and keeps the other",
          len(after) == len(before_del) - 1 and len(c3_after) == len(c3) - 1,
          "%d notes -> %d; C3 count %d -> %d" % (len(before_del), len(after),
                                                 len(c3), len(c3_after)))

    # Cross-check the final state through the Python descriptor reader, a different path
    # from the controller listing used above.
    clips = read_track(b, t.idx)["clips"]
    via_reader = sorted((n["key"], round(n["start"], 3)) for c in clips for n in c["notes"])
    via_listing = sorted((n[1], round(n[2], 3)) for n in after)
    check("controller listing agrees with the descriptor reader",
          via_reader == via_listing,
          "reader=%s listing=%s" % (via_reader, via_listing))

    print("final notes:", json.dumps(after))
    b.stop()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("\n==== %d/%d passed ====" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
