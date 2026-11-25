"""Look up a 6.1.5 Bitwig name and its 6.1.7 counterpart, for porting one call site.

  python lookup.py <class> [<member> [<arity>]]   class: SsT, com/bitwig/flt/app/eY1 or com.bitwig.flt.app.eY1
  python lookup.py --who <member> [<arity>]        6.1.5 classes declaring <member> (to find a receiver)
  python lookup.py --rev <6.1.7 class>             its 6.1.5 class

For a member, every overload with that name visible on the class (declared or
inherited) is listed with its declaring class, and its 6.1.7 name with the
evidence from name_table.resolve_member when Matcher left it unmatched.
"""
import pickle, sys
from pathlib import Path

import name_table as nt

HERE = Path(__file__).resolve().parent
MATCHES = HERE.parent.parent / "scratch" / "bitwig-615-617.match"
CACHE = HERE.parent.parent / "scratch" / "lookup-cache.pickle"


def state():
    if CACHE.exists() and CACHE.stat().st_mtime > MATCHES.stat().st_mtime:
        return pickle.loads(CACHE.read_bytes())
    A, B = nt.load(nt.JAR_A), nt.load(nt.JAR_B)
    cmap, mmap = nt.read_matches(MATCHES)
    subs = {}
    for n, (sup, ifs, _, _) in A.items():
        for p in [sup, *ifs]:
            if p:
                subs.setdefault(p, []).append(n)
    s = (A, B, cmap, mmap, subs)
    CACHE.write_bytes(pickle.dumps(s))
    return s


def arity(desc):
    inner, n, i = desc[1:desc.index(")")], 0, 0
    while i < len(inner):
        while inner[i] == "[":
            i += 1
        i = inner.index(";", i) + 1 if inner[i] == "L" else i + 1
        n += 1
    return n


def visible(A, cls, name):
    """(kind, name, desc, declaring class) of every member called <name> on cls or its supertypes."""
    out, seen, queue = [], set(), [cls]
    while queue:
        c = queue.pop(0)
        if c in seen or c not in A:
            continue
        seen.add(c)
        for (k, n, d) in A[c][3]:
            if n == name and not any(o[2] == d and o[0] == k for o in out):
                out.append((k, n, d, c))
        sup, ifs = A[c][0], A[c][1]
        queue.extend([sup, *ifs] if sup else ifs)
    return out


def to_b(A, B, cmap, mmap, subs, k, n, d, decl):
    hit = mmap.get((decl, k, nt.member_id(k, n, d)))
    if hit is not None:
        return nt.split_id(k, hit)[0], "Matcher"
    bdecl = cmap.get(decl)
    if bdecl is None:
        return None, "class unmatched"
    bid, ev = nt.resolve_member(A, B, cmap, mmap, subs, decl, bdecl, k, n, d)
    return (nt.split_id(k, bid)[0], ev) if bid else (None, "UNRESOLVED")


def main():
    A, B, cmap, mmap, subs = state()
    args = sys.argv[1:]
    if args[0] == "--rev":  # 6.1.7 class -> 6.1.5 class
        want = args[1].replace(".", "/")
        print(" ".join(a for a, b in cmap.items() if b == want or b.rsplit("/", 1)[-1] == want) or "no 6.1.5 class")
        return
    if args[0] == "--who":
        name, n = args[1], int(args[2]) if len(args) > 2 else None
        for c in sorted(A):
            for (k, m, d) in A[c][3]:
                if m == name and (n is None or (k == "M" and arity(d) == n)):
                    print(f"{c}\t{k} {m}{d}")
        return
    cls = args[0].replace(".", "/")
    if cls not in A:
        print(f"{cls}: not in 6.1.5 bitwig.jar"); return
    print(f"class {cls} -> {cmap.get(cls, 'UNMATCHED')}")
    if len(args) == 1:
        return
    name, n = args[1], int(args[2]) if len(args) > 2 else None
    for (k, m, d, decl) in visible(A, cls, name):
        if n is not None and (k != "M" or arity(d) != n):
            continue
        bname, ev = to_b(A, B, cmap, mmap, subs, k, m, d, decl)
        print(f"  {k} {m}{d}  [declared in {decl}]\n     -> {cmap.get(decl)}.{bname}{nt.translate_desc(d, cmap)}  ({ev})")


if __name__ == "__main__":
    main()
