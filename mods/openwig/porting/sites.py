"""List every reflective Bitwig name left in a source, with its 6.1.5 declarers and 6.1.7 names.

  python sites.py <file.java> [FROM_LINE TO_LINE]

One line per site: line number, helper, name/arity, then each 6.1.5 class
declaring that name with that arity and what it becomes in 6.1.7. A site with
one declarer can be typed directly; several declarers need the context.
"""
import re, sys

import lookup as lk
import name_table as nt

PATTERNS = [
    ("get", re.compile(r'modelGet(?:OrNull)?\([^"]*?"([^"]+)"\)'), 0),
    ("setter", re.compile(r'invokeModelSetter\([^"]*?"([^"]+)"'), 1),
    ("double-arg", re.compile(r'modelDoubleArgument\([^"]*?"([^"]+)"'), 1),
    ("method", re.compile(r'(?:getMethod|getDeclaredMethod)\("([^"]+)"((?:\s*,\s*[^,()]+(?:\([^()]*\))?)*)\)'), None),
    ("declared", re.compile(r'declaredMethod\([^"]*?"([^"]+)"((?:\s*,\s*[^,()]+(?:\([^()]*\))?)*)\)'), None),
    ("field", re.compile(r'(?:getDeclaredField|getField)\("([^"]+)"\)'), "F"),
    ("proxy", re.compile(r'getName\(\)\.equals\("([^"]+)"\)'), "any"),
    ("callApi", re.compile(r'callApi\([^"]*?"([^"]+)"((?:\s*,\s*[^,()]+(?:\([^()]*\))?)*)\)'), None),
    ("class", re.compile(r'classForName\("([^"]+)"\)'), "C"),
]
OBF = re.compile(r'^[A-Za-z_$][\w$]{0,2}$')


def main():
    path = sys.argv[1]
    lo, hi = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (1, 10**9)
    A, B, cmap, mmap, subs = lk.state()
    by_name = {}
    for c, info in A.items():
        for (k, m, d) in info[3]:
            by_name.setdefault(m, []).append((c, k, d))
    for no, line in enumerate(open(path, encoding="utf-8"), 1):
        if not lo <= no <= hi:
            continue
        for kind, rx, n in PATTERNS:
            for m in rx.finditer(line):
                name = m.group(1)
                if kind == "class":
                    cls = name.replace(".", "/")
                    print(f"{no}\tclass {name} -> {cmap.get(cls, 'NOT IN 6.1.5' if cls not in A else 'UNMATCHED')}")
                    continue
                if not OBF.match(name):
                    continue
                arity = n
                if arity is None:
                    extra = m.group(2) or ""
                    arity = len([a for a in re.split(r",(?![^()]*\))", extra) if a.strip()])
                cands = []
                for (c, k, d) in by_name.get(name, []):
                    if arity == "F" and k != "F":
                        continue
                    if isinstance(arity, int) and (k != "M" or lk.arity(d) != arity):
                        continue
                    if arity == "any" and k != "M":
                        continue
                    bname, ev = lk.to_b(A, B, cmap, mmap, subs, k, name, d, c)
                    cands.append(f"{c.rsplit('/', 1)[-1]}{'' if k == 'F' else d}->{cmap.get(c, '?').rsplit('/', 1)[-1]}.{bname}")
                tag = "ONE" if len(cands) == 1 else f"{len(cands)}x"
                shown = cands if len(cands) <= 6 else cands[:6] + [f"... +{len(cands) - 6}"]
                print(f"{no}\t{kind} {name}/{arity} {tag}\t" + " | ".join(shown))


if __name__ == "__main__":
    main()
