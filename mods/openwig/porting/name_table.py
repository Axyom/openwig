"""Turn Matcher's 6.1.5 -> 6.1.7 matches into a reviewed table for the names OpenWig uses.

usage: python name_table.py <matches> <names.tsv> [out.tsv]
names.tsv rows (6.1.5 names): C<TAB>class  |  M<TAB>owner<TAB>name<TAB>desc  |  F<TAB>owner<TAB>name<TAB>desc

Evidence for each row, checked independently of Matcher:
  string anchor  the class holds a string constant no other class in its jar has,
                 and its 6.1.7 match holds the same one
  signature      the member's descriptor, with every class translated, is exactly
                 the 6.1.7 member's descriptor, and no other member of the 6.1.7
                 class with that descriptor exists
  structure      Matcher's similarity only: review by hand
"""
import re, struct, sys, zipfile
from collections import Counter, defaultdict

JAR_A = r"C:\Program Files\Bitwig Studio\bin\bitwig.jar.openwig-original-6.1.5"
JAR_B = r"C:\Program Files\Bitwig Studio\bin\bitwig.jar"


def parse_class(data):
    """Constant-pool strings, this/super/interfaces and member (name, desc) of a class file."""
    count = struct.unpack_from(">H", data, 8)[0]
    pool, i, k = [None] * count, 10, 1
    while k < count:
        tag = data[i]
        if tag == 1:
            n = struct.unpack_from(">H", data, i + 1)[0]
            pool[k] = ("utf8", data[i + 3:i + 3 + n].decode("utf-8", "replace")); i += 3 + n
        elif tag in (7, 8, 16, 19, 20):
            pool[k] = (tag, struct.unpack_from(">H", data, i + 1)[0]); i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9; k += 1
        elif tag == 15:
            i += 4
        else:
            raise ValueError(f"tag {tag}")
        k += 1
    utf = lambda idx: pool[idx][1]
    strings = {utf(e[1]) for e in pool if e and e[0] == 8}
    i += 2
    this, sup, nint = struct.unpack_from(">HHH", data, i); i += 6
    name = utf(pool[this][1]); sup = utf(pool[sup][1]) if sup else None
    ifaces = [utf(pool[struct.unpack_from(">H", data, i + 2 * j)[0]][1]) for j in range(nint)]; i += 2 * nint
    members = {}
    for kind in "FM":
        n = struct.unpack_from(">H", data, i)[0]; i += 2
        for _ in range(n):
            _, ni, di, na = struct.unpack_from(">HHHH", data, i); i += 8
            members[(kind, utf(ni), utf(di))] = True
            for _ in range(na):
                ln = struct.unpack_from(">I", data, i + 2)[0]; i += 6 + ln
    return name, sup, ifaces, strings, members


def load(path):
    classes = {}
    with zipfile.ZipFile(path) as z:
        for e in z.namelist():
            if e.endswith(".class") and not e.startswith("META-INF"):
                n, sup, ifs, strs, mem = parse_class(z.read(e))
                classes[n] = (sup, ifs, strs, mem)
    return classes


def unique_strings(classes):
    seen = Counter(s for c in classes.values() for s in c[2])
    return {n: {s for s in c[2] if seen[s] == 1 and len(s) > 3} for n, c in classes.items()}


def read_matches(path):
    cls, mem = {}, {}
    cur = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("c\t"):
            _, a, b = line.split("\t")
            cur = a[1:-1]; cls[cur] = b[1:-1]
        elif line.startswith("\tm\t") or line.startswith("\tf\t"):
            _, kind, a, b = line.split("\t")
            mem[(cur, kind.upper(), a)] = b
        elif not line.startswith("\t"):
            cur = None
    return cls, mem


def translate_desc(desc, cls):
    return re.sub(r"L([^;]+);", lambda m: "L" + cls.get(m.group(1), m.group(1)) + ";", desc)


def member_id(kind, name, desc):
    return name + desc if kind == "M" else name + ";;" + desc


def split_id(kind, mid):
    if kind == "F":
        return mid.split(";;", 1)
    j = mid.index("(")
    return mid[:j], mid[j:]


def declarer(classes, owner, kind, name, desc):
    """The class that declares a member a reference names through `owner` (it may inherit it)."""
    seen, queue = set(), [owner]
    while queue:
        c = queue.pop(0)
        if c in seen or c not in classes:
            continue
        seen.add(c)
        if (kind, name, desc) in classes[c][3]:
            return c
        sup, ifs = classes[c][0], classes[c][1]
        queue.extend([sup, *ifs] if sup else ifs)
    return None


def resolve_member(A, B, cmap, mmap, subs, owner, bo, kind, name, desc):
    """A member Matcher left unmatched in a matched class (interfaces have no code to compare)."""
    bdesc = translate_desc(desc, cmap)
    in_b = lambda n: declarer(B, bo, kind, n, bdesc) is not None
    if len(name) > 3 and in_b(name):
        return member_id(kind, name, bdesc), "same readable name"
    # an override keeps the overridden method's name: find a matched one in a 6.1.5 subtype
    seen, queue = set(), list(subs.get(owner, []))
    while queue:
        sub = queue.pop(0)
        if sub in seen:
            continue
        seen.add(sub)
        hit = mmap.get((sub, kind, member_id(kind, name, desc)))
        if hit is not None:
            bname, _ = split_id(kind, hit)
            if in_b(bname):
                return member_id(kind, bname, bdesc), f"via implementor {sub} -> {cmap.get(sub)}"
        queue.extend(subs.get(sub, []))
    same = [m for m in B[bo][3] if m[0] == kind and m[2] == bdesc]
    if len(same) == 1:
        return member_id(kind, same[0][1], bdesc), "signature (only member with this descriptor)"
    return None, None


def main():
    matches, names = sys.argv[1], sys.argv[2]
    out = open(sys.argv[3], "w", encoding="utf-8") if len(sys.argv) > 3 else sys.stdout
    A, B = load(JAR_A), load(JAR_B)
    uA, uB = unique_strings(A), unique_strings(B)
    cmap, mmap = read_matches(matches)
    subs = defaultdict(list)  # 6.1.5 class -> direct subclasses and implementors
    for n, (sup, ifs, _, _) in A.items():
        for p in [sup, *ifs]:
            if p:
                subs[p].append(n)
    print("kind\t6.1.5\t6.1.7\tevidence", file=out)
    todo = Counter()
    for row in open(names, encoding="utf-8"):
        f = row.rstrip("\n").split("\t")
        if not f[0]:
            continue
        if f[0] == "C":
            a = f[1]
            if a not in A:
                print(f"C\t{a}\t-\tnot in 6.1.5 bitwig.jar (libs or JDK)", file=out); continue
            b = cmap.get(a)
            if b is None:
                ev = "UNMATCHED"
            elif uA[a] & uB.get(b, set()):
                ev = "string anchor: " + repr(sorted(uA[a] & uB[b])[0])[:60]
            elif a == b:
                ev = "same readable name"
            else:
                ev = "structure"
            todo[ev.split(":")[0]] += 1
            print(f"C\t{a}\t{b or '-'}\t{ev}", file=out)
            continue
        kind, owner, name, desc = f[0], f[1], f[2], f[3]
        if owner not in A:
            print(f"{kind}\t{owner}.{name}{desc}\t-\tnot in 6.1.5 bitwig.jar", file=out); continue
        decl = declarer(A, owner, kind, name, desc)
        if decl is None:
            print(f"{kind}\t{owner}.{name}{desc}\t-\tdeclared outside bitwig.jar (JDK or libs)", file=out); continue
        bo = cmap.get(owner)
        bid = mmap.get((decl, kind, member_id(kind, name, desc)))
        if bid is not None:  # Matcher's pair is on the declaring class; the reference keeps its own owner
            bid = member_id(kind, split_id(kind, bid)[0], translate_desc(desc, cmap))
        if bo is not None and bid is None and decl != owner and decl in cmap:
            bid, _ = resolve_member(A, B, cmap, mmap, subs, decl, cmap[decl], kind, name, desc)
        if bo is not None and bid is None:
            bid, ev = resolve_member(A, B, cmap, mmap, subs, owner, bo, kind, name, desc)
            if bid is not None:
                bname, bdesc = split_id(kind, bid)
                todo[ev.split(" ")[0]] += 1
                print(f"{kind}\t{owner}.{name}{desc}\t{bo}.{bname}{bdesc}\t{ev}", file=out)
                continue
        if bo is None or bid is None:
            ev, shown = "UNMATCHED", "-"
        else:
            bname, bdesc = split_id(kind, bid)
            shown = f"{bo}.{bname}{bdesc}"
            bdecl = declarer(B, bo, kind, bname, bdesc)
            same_shape = [m for m in B[bdecl][3] if m[0] == kind and m[2] == bdesc] if bdecl else []
            if bdecl is None:
                ev = "BROKEN: 6.1.7 class has no such member"
            elif len(name) > 3 and name == bname:
                ev = "same readable name"
            elif translate_desc(desc, cmap) == bdesc and len(same_shape) == 1:
                ev = "signature"
            elif translate_desc(desc, cmap) == bdesc:
                ev = f"structure ({len(same_shape)} members share the descriptor)"
            else:
                ev = "structure (descriptor changed)"
        todo[ev.split(" ")[0]] += 1
        print(f"{kind}\t{owner}.{name}{desc}\t{shown}\t{ev}", file=out)
    print("summary:", dict(todo), file=sys.stderr)


if __name__ == "__main__":
    main()
