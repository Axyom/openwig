"""Re-resolve the descriptor reader on a re-obfuscated Bitwig build (6.1 port).

Creates ONE probe track whose NAME is the sentinel, selects it, and asks the controller
to rank every structural reader candidate by whether its walk surfaces that name. Needs
no clip/automation write, so it works on a build where those cannot be verified yet.

Never deletes the probe track: host.deleteObjects() crashed Bitwig 6.1 after a probe
write. Close the project without saving to clean up.

    python tools/dev_reader.py
"""
import json
import sys
import time

from openwig.bridge import BridgeClient

SENTINEL = "OWSENT7F3A"


def occupied(b):
    return {t.get("index") for t in b.request("state.snapshot").get("tracks", []) if t.get("name")}


def main():
    b = BridgeClient(request_timeout=30)
    b.start()
    if not b.wait_connected(8):
        print("NOT CONNECTED - is Bitwig running with OpenwigBridge enabled?")
        return 1

    print("host:", b.request("host.version"))
    print("symbols:", json.dumps(b.request("dev.symbols"), indent=2)[:1200])

    snap = b.request("state.snapshot")
    names = [(t.get("index"), t.get("name")) for t in snap.get("tracks", []) if t.get("name")]
    print("tracks:", names)

    # probe track named with the sentinel (track.create is gate-exempt)
    before = occupied(b)
    b.request("track.create", {"type": "instrument", "name": SENTINEL, "index": -1})
    idx, got_name = None, None
    for _ in range(16):
        time.sleep(0.3)
        new = sorted(occupied(b) - before)
        if new:
            idx = new[-1]
            got_name = next((t.get("name") for t in b.request("state.snapshot").get("tracks", [])
                             if t.get("index") == idx), None)
            break
    if idx is None:
        print("probe track did not appear")
        return 2
    print(f"probe track idx={idx} name={got_name!r}")
    b.request("track.select", {"index": idx})
    time.sleep(0.6)

    sentinels = [s for s in {SENTINEL, got_name} if s]
    b.request_op("dev.reader_rank", {"sentinels": sentinels, "limit": 12}, timeout=240)
    res = b.request("dev.result", timeout=30)
    if res.get("error"):
        print("ERROR:", res["error"])
        return 3
    cands = (res.get("result") or {}).get("candidates") or []
    print(f"\n{len(cands)} reader candidates (sentinels={sentinels}):")
    for i, c in enumerate(cands):
        print(f"\n[{i}] hits={c['hits']} scalars={c['scalars']}")
        print("    mX_={mX_} KRt={KRt} bf={bf} ngq={ngq} nI_={nI_} Xzy={Xzy} uEK={uEK}".format(**c))
        print("    sample:", c["sample"][:6])

    winners = [c for c in cands if c["hits"]]
    if not winners:
        print("\nNo candidate surfaced the sentinel - the reader shape itself changed.")
        return 4

    w = winners[0]
    reader = {k: w[k] for k in ("mX_", "KRt", "bf", "ngq", "nI_", "Xzy", "uEK")}
    print("\nbest reader:", json.dumps(reader))
    b.request_op("dev.walk", {"reader": reader, "keep": True, "max_chars": 3000}, timeout=120)
    res = b.request("dev.result", timeout=30)
    r = res.get("result") or {}
    print(f"\nwalk with that reader: {r.get('length')} chars")
    print(r.get("json", "")[:2500])
    print("\n(applied to this session; probe track left in place - close without saving)")
    b.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
