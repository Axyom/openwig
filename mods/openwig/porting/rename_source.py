"""Rename Java identifiers outside string literals, char literals and comments.

  python rename_source.py <file.java> OLD=NEW [OLD=NEW ...]
  python rename_source.py <file.java> --at LINE OLD=NEW   (only on that line)

Used to move a source from one Bitwig build's names to another's. Whole
identifiers only: `IYM` is renamed, `IYMx` and "IYM" are not.
"""
import re, sys

TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*[\s\S]*?\*/|[A-Za-z_$][\w$]*|[\s\S]')


def rename(text, mapping, line=None):
    out, current = [], 1
    for m in TOKEN.finditer(text):
        tok = m.group(0)
        if (line is None or current == line) and tok in mapping and (tok[0].isalpha() or tok[0] in "_$"):
            tok = mapping[tok]
        current += m.group(0).count("\n")
        out.append(tok)
    return "".join(out)


def main():
    path, args = sys.argv[1], sys.argv[2:]
    line = None
    if args and args[0] == "--at":
        line, args = int(args[1]), args[2:]
    mapping = dict(a.split("=", 1) for a in args)
    text = open(path, encoding="utf-8").read()
    new = rename(text, mapping, line)
    open(path, "w", encoding="utf-8", newline="").write(new)
    print(f"{path}: {sum(1 for a, b in zip(TOKEN.findall(text), TOKEN.findall(new)) if a != b)} identifier(s) renamed")


if __name__ == "__main__":
    main()
