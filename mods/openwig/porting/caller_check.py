"""Cross-check "structure" rows of a name table by their callers.

For a 6.1.5 member mapped to a 6.1.7 member, the classes that call it in 6.1.5,
translated with Matcher's class matches, should be the classes that call the
6.1.7 member. Rewrites the evidence column of each structure row to
"callers: <agree>/<total>" and prints the rows that disagree.
usage: python caller_check.py <matches> <table.tsv>
"""
import subprocess, sys
from name_table import read_matches

JS = r"C:\Users\Axyom\AppData\Local\Programs\BML-0.36.0-dev.17-windows\docs\sdk\jarsearch.py"
JAR_A = r"C:\Program Files\Bitwig Studio\bin\bitwig.jar.openwig-original-6.1.5"
JAR_B = r"C:\Program Files\Bitwig Studio\bin\bitwig.jar"


def callers(jar, owner, name, desc):
    out = subprocess.run([sys.executable, JS, jar, "callers", owner, name, desc],
                         capture_output=True, text=True).stdout
    return {line.split("\t")[0] for line in out.splitlines() if "\t" in line}


def split(ref, kind):
    j = ref.index("(") if kind == "M" else ref.index("L", ref.rindex(".")) if False else None
    if kind == "M":
        head, desc = ref[:ref.index("(")], ref[ref.index("("):]
    else:  # field rows print owner.nameDESC; the name is the obfuscated run before the descriptor
        head, desc = ref, ""
    owner, name = head.rsplit(".", 1)
    return owner, name, desc


def main():
    cmap, _ = read_matches(sys.argv[1])
    path = sys.argv[2]
    rows = [line.rstrip("\n").split("\t") for line in open(path, encoding="utf-8")]
    bad = []
    for r in rows[1:]:
        if len(r) < 4 or not r[3].startswith("structure") or r[0] != "M":
            continue
        oa, na, da = split(r[1], "M")
        ob, nb, db = split(r[2], "M")
        ca, cb = callers(JAR_A, oa, na, da), callers(JAR_B, ob, nb, db)
        mapped = {cmap.get(c, c) for c in ca}
        agree = len(mapped & cb)
        r[3] = f"callers: {agree}/{len(ca)} agree ({r[3]})"
        if agree < len(ca) or not ca:
            bad.append(r)
    with open(path, "w", encoding="utf-8") as out:
        for r in rows:
            out.write("\t".join(r) + "\n")
    for r in bad:
        print("CHECK", "\t".join(r))
    print(f"{len(bad)} structure rows need a look")


if __name__ == "__main__":
    main()
