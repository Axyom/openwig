"""Replace classForName("<6.1.5 class>") with the 6.1.7 class literal.

  python class_literals.py <file.java>

Default-package classes live in bitwig.obf in the compile jar; readable classes keep their name.
"""
import re, sys
import lookup as lk

def main():
    path = sys.argv[1]
    A, B, cmap, mmap, subs = lk.state()
    text = open(path, encoding="utf-8").read()
    missing = []
    def sub(m):
        old = m.group(1).replace(".", "/")
        new = cmap.get(old)
        if new is None:
            if old in A:
                missing.append(old); return m.group(0)
            new = old  # not obfuscated-only: readable classes keep their name
        if "/" not in new:
            new = "bitwig/obf/" + new
        return new.replace("/", ".").replace("$", ".") + ".class"
    out, n = re.subn(r'classForName\("([^"]+)"\)', sub, text)
    open(path, "w", encoding="utf-8", newline="").write(out)
    print(f"{n} literal(s); unmatched: {missing}")

if __name__ == "__main__":
    main()
