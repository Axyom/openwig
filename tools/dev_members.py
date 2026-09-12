"""Inspect the 6.1 'member' objects the descriptor value-getter returns, and re-run the
resolver probe now that a working reader is applied to the session.

On 6.1 Xzy(uo1) yields an object whose toString embeds the value
(e.g. GNy[member_name=title,object=...,value=X]) instead of the raw value, so the
reader needs one more hop. This dumps those classes' methods to find it.

Does NOT delete any track (host.deleteObjects crashed Bitwig 6.1 after a probe write).
"""
import json
import sys
import time

from openwig.bridge import BridgeClient

MEMBER_CLASSES = ["GNy", "Tl3", "XxJ", "ya", "ELu", "com.bitwig.ramona.core.qt"]


def main():
    b = BridgeClient(request_timeout=30)
    b.start()
    if not b.wait_connected(8):
        print("NOT CONNECTED")
        return 1

    print("symbols in session:", json.dumps(b.request("dev.symbols").get("sym", {}).get("mX_", "?")))
    sym = b.request("dev.symbols").get("sym", {})
    print("reader now:", {k: sym.get(k) for k in ("mX_", "KRt", "bf", "ngq", "nI_", "Xzy", "uEK")})

    for cls in MEMBER_CLASSES:
        r = b.request("dev.methods", {"of": cls, "limit": 60})
        if r.get("error"):
            print(f"\n--- {cls}: ERROR {r['error']}")
            continue
        print(f"\n--- {cls} ({r.get('count')} methods) ---")
        for m in r.get("methods", []):
            print(f"    {m['owner']}.{m['sig']} -> {m['ret']}")

    # re-run the resolver probe on the currently selected track with the good reader live
    print("\n=== resolver.probe with the 6.1 reader applied ===")
    b.request("track.select", {"index": 0})
    time.sleep(0.5)
    b.request_op("resolver.probe", {"blind": False}, timeout=180)
    res = b.request("resolver.result", timeout=30)
    rep = res.get("report") or {}
    print("error:", res.get("error"))
    print("classes:", json.dumps(rep.get("classes")))
    for k, v in (rep.get("capabilities") or {}).items():
        print(f"  {k:18} ok={v.get('ok')}  {v.get('detail')}")
    print("reader:", json.dumps(rep.get("reader")))
    print("commands:", json.dumps(rep.get("commands"))[:400])
    print("cache:", json.dumps(rep.get("cache")))
    b.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
