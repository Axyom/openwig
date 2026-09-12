"""openwig.read.notes - read MIDI notes + automation from arranger tracks, LIVE.

Uses the controller's generic in-process descriptor-graph reader (obj.walk) to
walk Bitwig's own document objects and pull real note/automation values - no wire
parsing. Arranger note clips live behind FvY-filtered relationships, so we walk
with no_filter and prune the device subtrees.

Object model (discovered in the bitwig-re research):
  main_note_clip_lane -> note_clip_event_timeline -> instrument_note_clip_event (a CLIP)
    -> ... -> instrument_note_event_timeline (one per KEY) -> instrument_note_event (a NOTE)
"""
import sys
import time
import json
from pathlib import Path

from openwig.bridge import BridgeClient

# Subtrees we never need (devices/aux/launcher) - pruned to keep the walk small
# AND to STOP it leaving the current track (track_mixer_module -> send_group ->
# track_group -> sibling track would otherwise pull in every OTHER track).
PRUNE = ["native_device", "device_contents", "nitro_atom", "polyphonic_note_voice_atom",
         "nested_device_chain", "remote_controls_page", "device_chain", "remote_control",
         "launcher_note_clip_slots", "launcher_automation_clip_slots", "modulation_source_atom",
         "track_mixer_module",
         # Bitwig 6.1 reports generic CATEGORY names instead of document class names, so
         # none of the names above match there. These are subtrees a clip read never needs.
         #
         # "track" is deliberately NOT pruned: on 6.1 the path to a track's own clips runs
         # through a nested node that is itself categorised "track"
         # (track -> track -> timeline -> event), so pruning that category severs the clip
         # lane entirely. Sibling tracks are excluded during collection instead - see
         # _skip_nested_track below.
         "device", "routing", "port", "matrix", "analysis",
         "master", "browser", "selection", "recording"]

# Categories that must survive pruning for a clip/note read to work at all, i.e. the
# classes seen on the path root -> clip -> note: track, timeline, event, shared_timelines.


def _skip_nested_track(child, depth):
    """True if `child` is a SIBLING track that the walk wandered into.

    A per-track read must not report other tracks' clips. The track's own clip lane sits
    under a nested "track" node one level below the root, while sibling tracks appear
    deeper, so a nested track is only an escape when it is found below depth 1.
    """
    return depth >= 1 and isinstance(child, dict) and child.get("_cls") == "track"

CLIP_CLS = "instrument_note_clip_event"
KEY_TL_CLS = "instrument_note_event_timeline"
NOTE_CLS = "instrument_note_event"
LANE_CLS = ("permanent_automation_lane", "about_to_be_created_automation_lane")
BREAKPOINT_CLS = "decimal_value_event"

# prop ids (notes)
P_TIME, P_DUR, P_VON, P_VOFF = "687", "38", "239", "240"
P_KEY, P_CHAN = "238", "9857"
P_CLIP_NAME = "2958"
# Arranger clip position/length: 6.1 carries them on the clip event as a dedicated
# pair; 6.0.x uses the generic time/duration pair. Both are accepted.
P_CLIP_START, P_CLIP_LEN = "11280", "11279"


# ── structural (class-name-free) recognition ────────────────────────────────────
# The numeric property ids are wire-protocol data and are IDENTICAL on 6.0.x and 6.1.
# The reader's CLASS NAMES are not: 6.0.x reports document class names
# ("instrument_note_event"), while on 6.1 the resolved name-getter yields generic
# categories ("timeline", "event"). Nodes are therefore identified by which properties
# they carry; the *_CLS constants stay as documentation of the 6.0.x model.

def _is_note(node):
    """A note event: start + duration + note-on velocity."""
    return P_TIME in node and P_DUR in node and P_VON in node


def _is_key_timeline(node):
    """The per-key timeline carrying the pitch + MIDI channel of the notes beneath it."""
    return P_KEY in node and P_CHAN in node


def _clip_bounds(node):
    """(start, duration) if the node looks like an arranger clip event, else None.

    Position and length live in the SAME generic time/duration props on both 6.0.x and
    6.1 (687 = arranger start, 38 = length). 6.1 additionally carries P_CLIP_LEN, which
    mirrors the length, and P_CLIP_START, which is always 0 - reading position from the
    latter makes every clip look like it sits at bar 1, so 687/38 take precedence and the
    dedicated pair is only a fallback.

    A clip event is told apart from a NOTE event by the absence of a velocity: note events
    carry the same time/duration props, so requiring "no note-on velocity" is what keeps a
    note from being reported as a clip.
    """
    if P_CLIP_LEN in node and P_VON not in node:
        start = node.get(P_TIME, node.get(P_CLIP_START))
        dur = node.get(P_DUR, node.get(P_CLIP_LEN))
        return _num(start), _num(dur)
    if node.get("_cls") == CLIP_CLS and P_TIME in node:
        return _num(node.get(P_TIME)), _num(node.get(P_DUR))
    return None


# prop ids (automation breakpoint = decimal_value_event)
P_BP_TIME, P_BP_VALUE, P_BP_INTERP = "687", "655", "13726"

NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def note_name(k):
    try:
        k = int(k)
        return f"{NAMES[k % 12]}{k // 12 - 2}"
    except Exception:
        return "?"


def walk_track(bridge, idx, max_depth=16, max_nodes=40000):
    bridge.request("track.select", {"index": idx})
    time.sleep(0.8)            # let cursorTrack follow the selection before walking
    # root="track": an instrument track with no device has no device target at all,
    # and clips must still be readable. ignore_nI: on 6.1 the per-descriptor
    # "is serialized" gate hides the note/clip properties, so the notes only surface
    # with it off (harmless on 6.0.x, which merely walks a few more properties).
    bridge.request("obj.walk", {"max_depth": max_depth, "max_nodes": max_nodes,
                                "no_filter": True, "prune": PRUNE, "root": "track",
                                "ignore_nI": True,
                                "no_dedup": ["device_atom_reference"]})
    time.sleep(1.0)
    r = None
    for _ in range(60):
        r = bridge.request("obj.walk_result")
        if r and r.get("ready"):
            break
        time.sleep(0.3)
    if not r or not r.get("ready"):
        raise RuntimeError("walk timed out")
    if r.get("error"):
        raise RuntimeError(r["error"])
    return json.loads(r["json"])


def _num(v, d=0.0):
    try:
        return float(v)
    except Exception:
        return d


def collect_notes(node, cur_key=None, cur_chan=0, out=None, depth=0):
    """Recurse the walk tree; emit a note dict per instrument_note_event,
    inheriting key/channel from the nearest enclosing key-timeline."""
    if out is None:
        out = []
    if not isinstance(node, dict):
        return out
    if _is_key_timeline(node):
        cur_key = node.get(P_KEY, cur_key)
        cur_chan = node.get(P_CHAN, cur_chan)
    if _is_note(node):
        out.append({
            "key": int(_num(cur_key, -1)),
            "name": note_name(cur_key),
            "channel": int(_num(cur_chan, 0)),
            "start": round(_num(node.get(P_TIME)), 6),
            "duration": round(_num(node.get(P_DUR)), 6),
            "velocity": round(_num(node.get(P_VON)), 4),
            "release_velocity": round(_num(node.get(P_VOFF)), 4),
        })
    for v in node.values():
        if isinstance(v, list):
            for it in v:
                if _skip_nested_track(it, depth):
                    continue
                collect_notes(it, cur_key, cur_chan, out, depth + 1)
    return out


def collect_clips(node, out=None, depth=0):
    """Find every note CLIP (instrument_note_clip_event) and the notes inside it."""
    if out is None:
        out = []
    if not isinstance(node, dict):
        return out
    bounds = _clip_bounds(node)
    if bounds is not None:
        notes = collect_notes(node)
        notes.sort(key=lambda n: (n["start"], n["key"]))
        out.append({
            "clip_start": round(bounds[0], 6),
            "clip_duration": round(bounds[1], 6),
            "name": node.get(P_CLIP_NAME, ""),
            "note_count": len(notes),
            "notes": notes,
        })
        return out  # don't double-descend into the clip's own content timelines
    for v in node.values():
        if isinstance(v, list):
            for it in v:
                if _skip_nested_track(it, depth):
                    continue
                collect_clips(it, out, depth + 1)
    return out


def _param_name(node):
    """Best-effort name of the parameter an automation lane targets: the nearest
    value-atom class found under the lane, plus any title string on it."""
    found = [None]

    def rec(o):
        if found[0] or not isinstance(o, dict):
            return
        cls = o.get("_cls", "")
        if cls.endswith("_value_atom") or cls.endswith("_atom"):
            title = o.get("347") or o.get("2958") or ""
            found[0] = f"{cls}" + (f" ({title})" if title else "")
            return
        for v in o.values():
            if isinstance(v, list):
                for it in v:
                    rec(it)
    rec(node)
    return found[0] or "?"


def collect_breakpoints(node, out=None):
    if out is None:
        out = []
    if not isinstance(node, dict):
        return out
    if node.get("_cls") == BREAKPOINT_CLS:
        out.append({
            "time": round(_num(node.get(P_BP_TIME)), 6),
            "value": round(_num(node.get(P_BP_VALUE)), 6),
            "interp": node.get(P_BP_INTERP, "LINEAR"),
        })
    for v in node.values():
        if isinstance(v, list):
            for it in v:
                collect_breakpoints(it, out)
    return out


def _ids_under(node, out):
    """All integer-ish ids (object _id + numeric props) under `node`."""
    if not isinstance(node, dict):
        return
    for k, v in node.items():
        if isinstance(v, int):
            out.add(v)
        elif isinstance(v, str) and v.lstrip("-").isdigit():
            out.add(int(v))
        elif isinstance(v, list):
            for it in v:
                _ids_under(it, out)


def _ref_ids(lane):
    """Candidate target-object ids found under the lane's device_atom_reference(s)."""
    out = set()

    def rec(n):
        if not isinstance(n, dict):
            return
        for v in n.values():
            if isinstance(v, list):
                for it in v:
                    if isinstance(it, dict) and it.get("_cls") == "device_atom_reference":
                        _ids_under(it, out)
                    rec(it)
    rec(lane)
    return out


def collect_automation(node, out=None):
    """Find automation lanes that actually carry breakpoints."""
    if out is None:
        out = []
    if not isinstance(node, dict):
        return out
    if node.get("_cls") in LANE_CLS:
        bps = collect_breakpoints(node)
        if bps:
            bps.sort(key=lambda b: b["time"])
            out.append({"param": _param_name(node), "breakpoint_count": len(bps),
                        "breakpoints": bps, "ref_ids": sorted(_ref_ids(node))})
        return out  # a lane's subtree is self-contained
    for v in node.values():
        if isinstance(v, list):
            for it in v:
                collect_automation(it, out)
    return out


def read_device_atom_map(bridge, idx, max_devices=16):
    """Map each device-remote-param's document-atom id -> (device_index, page, remote_index,
    device_name, param_name) for the track's device chain, across ALL remote-control pages
    (an automated parameter may be mapped on a page other than the default one). Selecting a
    page is async, so it's done one page at a time with a settle, not inside the handler."""
    bridge.request("track.select", {"index": idx}); time.sleep(0.3)
    for _ in range(max_devices):
        try: bridge.request("device.select_previous")
        except Exception: break
    time.sleep(0.2)
    amap = {}; last = None
    for di in range(max_devices):
        d = bridge.request("state.snapshot").get("device") or {}
        if not d.get("exists"):
            break
        nm = d.get("name")
        if nm == last and amap:
            break
        pages = bridge.request("device.all_remote_pages") or []
        npages = len(pages) if pages else 1
        for pgi in range(npages):
            bridge.request("device.select_remote_page", {"page": pgi}); time.sleep(0.12)
            rr = bridge.request("device.remote_atom_ids") or {}
            for pm in rr.get("params", []):
                for aid in pm.get("atom_ids", []):
                    amap.setdefault(aid, (di, pgi, pm.get("remote_index"), nm, pm.get("name", "")))
        bridge.request("device.select_remote_page", {"page": 0}); time.sleep(0.05)
        last = nm
        bridge.request("device.select_next"); time.sleep(0.1)
    return amap


def normalize_remote_lanes(bridge, idx, autos):
    """Convert each resolved remote-automation lane's breakpoint values from native units
    to automate()'s 0..1, using the controller's `device.remote_normalize` (Bitwig's OWN
    normalize function on the parameter's document fj). Exact for any mapping - including
    remotes whose macro covers only a sub-range of the parameter, and nonlinear (log)
    params - because it normalizes against the underlying parameter's full range, not the
    remote macro. Non-destructive (reads only). Stores the result on each breakpoint as
    `nvalue` and sets `target["normalized"] = True` when the whole lane converted."""
    by_dev = {}
    for a in autos:
        tg = a.get("target") or {}
        if tg.get("kind") == "remote" and tg.get("remote_index") is not None:
            by_dev.setdefault(tg["device_index"], []).append(a)
    if not by_dev:
        return
    bridge.request("track.select", {"index": idx}); time.sleep(0.2)
    for di in sorted(by_dev):
        bridge.request("device.select_index", {"index": int(di)}); time.sleep(0.3)
        for a in by_dev[di]:
            bps = a.get("breakpoints") or []
            ri = a["target"]["remote_index"]
            pg = a["target"].get("page", 0) or 0
            bridge.request("device.select_remote_page", {"page": int(pg)}); time.sleep(0.15)
            resp = bridge.request("device.remote_normalize",
                                  {"remote_index": int(ri), "values": [b.get("value") for b in bps]}) or {}
            norm = resp.get("normalized")
            if norm and len(norm) == len(bps) and all(isinstance(n, (int, float)) for n in norm):
                for b, n in zip(bps, norm):
                    b["nvalue"] = n
                a["target"]["normalized"] = True


def read_track(bridge, idx):
    tree = walk_track(bridge, idx)
    clips = collect_clips(tree)
    autos = collect_automation(tree)
    # resolve each lane's target: volume / pan by class, device params by id-matching
    needs_map = any("volume" not in (a.get("param") or "").lower()
                    and "pan" not in (a.get("param") or "").lower() for a in autos)
    amap = read_device_atom_map(bridge, idx) if needs_map else {}
    for a in autos:
        param = (a.get("param") or "").lower()
        if "volume" in param:
            a["target"] = {"kind": "volume"}
        elif "pan" in param:
            a["target"] = {"kind": "pan"}
        else:
            hit = next((amap[r] for r in a.get("ref_ids", []) if r in amap), None)
            a["target"] = ({"kind": "remote", "device_index": hit[0], "page": hit[1],
                            "remote_index": hit[2], "device": hit[3], "param": hit[4]}
                           if hit else {"kind": "unknown"})
        a.pop("ref_ids", None)
    # convert resolved remote lanes' breakpoint values native -> normalized (the walk
    # reads raw units; automate() needs 0..1) via Bitwig's own normalize function
    normalize_remote_lanes(bridge, idx, autos)
    return {"clips": clips, "automation": autos}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    idxs = [int(a) for a in args] if args else list(range(10))
    b = BridgeClient(request_timeout=15.0)
    b.start()
    if not b.wait_connected(5.0):
        print("NOT CONNECTED -- is Bitwig running with OpenwigBridge?")
        sys.exit(1)
    b.request("transport.stop")
    for idx in idxs:
        try:
            data = read_track(b, idx)
        except Exception as e:  # noqa: BLE001
            print(f"track {idx}: ERROR {e}")
            continue
        clips, autos = data["clips"], data["automation"]
        total = sum(c["note_count"] for c in clips)
        if not clips and not autos:
            print(f"track {idx}: (no note clips / automation)")
            continue
        print(f"track {idx}: {len(clips)} clip(s), {total} note(s), {len(autos)} automation lane(s)")
    b.stop()


if __name__ == "__main__":
    main()
