"""Inventory every Bitwig name the OpenWig bridge classes use.

1. Direct references: constant-pool Class/Fieldref/Methodref entries of the
   added classes in the patched jar (javap -v), owners outside the JDK, org.json
   and OpenWig's own classes.
2. Reflection strings: name literals in OpenwigDocument.java passed to
   getMethod/getDeclaredMethod/getField/getDeclaredField/forName and to the
   modelGet/modelGetOrNull/invokeModelSetter helpers.
"""
import re, subprocess, sys, json
from collections import defaultdict
from pathlib import Path

JAVAP = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\javap.exe"
JAR = "scratch/bitwig-6.1.5-openwig.jar"
CLASSES = ["OpenwigDocument", "OpenwigTransport", "com.openwig.bridge.Bootstrap",
           "com.openwig.bridge.DirectAccess", "com.bitwig.ramona.core.OpenwigUndo"]
OWN = ("OpenwigDocument", "OpenwigTransport", "com/openwig/", "com/bitwig/ramona/core/OpenwigUndo")
SKIP = ("java/", "javax/", "jdk/", "sun/", "org/json/", "[")

direct = defaultdict(lambda: defaultdict(set))   # owner -> (kind,name,desc) -> {users}
classes_used = defaultdict(set)
ref = re.compile(r"=\s+(Fieldref|Methodref|InterfaceMethodref|Class)\s+\S+\s+//\s+(.*)$")
for cls in CLASSES:
    out = subprocess.run([JAVAP, "-v", "-p", "-cp", JAR, cls], capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        m = ref.search(line)
        if not m:
            continue
        kind, rest = m.groups()
        if kind == "Class":
            owner = rest.strip().strip('"')
            if not owner.startswith(SKIP) and not owner.startswith(OWN):
                classes_used[owner].add(cls)
            continue
        target, _, nd = rest.partition(".")
        target = target.strip('"')
        name, _, desc = nd.partition(":")
        if target.startswith(SKIP) or target.startswith(OWN):
            continue
        direct[target][(kind, name.strip('"'), desc)].add(cls)

src = Path("java_bridge/src/OpenwigDocument.java").read_text("utf-8").splitlines()
lit = re.compile(r'(getMethod|getDeclaredMethod|getField|getDeclaredField|forName|modelGetOrNull|modelGet|invokeModelSetter)\(([^"]*?)"([^"]+)"')
reflect = defaultdict(list)
for i, line in enumerate(src, 1):
    for m in lit.finditer(line):
        api, _, name = m.groups()
        reflect[(api, name)].append(i)

obf = re.compile(r"^[A-Za-z][A-Za-z0-9_]{1,3}$")
print("== DIRECT REFERENCES ==")
for owner in sorted(direct):
    for (kind, name, desc), users in sorted(direct[owner].items()):
        print(f"{kind[0]}\t{owner}\t{name}\t{desc}\t{','.join(sorted(u.split('.')[-1] for u in users))}")
print("== CLASSES ==")
for owner in sorted(classes_used):
    print(f"C\t{owner}\t{','.join(sorted(u.split('.')[-1] for u in classes_used[owner]))}")
print("== REFLECTION STRINGS ==")
names = set()
calls = 0
for (api, name), lines in sorted(reflect.items(), key=lambda kv: (kv[0][1], kv[0][0])):
    calls += len(lines)
    names.add(name)
    print(f"{api}\t{name}\t{len(lines)}\t{','.join(map(str, lines[:12]))}{'...' if len(lines) > 12 else ''}")
print(f"== reflection: {len(names)} distinct names, {calls} call sites; obfuscated-looking: "
      f"{sorted(n for n in names if obf.match(n) and not n[0].islower() or obf.match(n) and any(c.isupper() for c in n))}")
