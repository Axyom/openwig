#!/bin/bash
# lk.sh CLASS m1 m2 ... : 0-arity getters (use m/N for arity N); prints compact 6.1.5 -> 6.1.7
cls=$1; shift
python - "$cls" "$@" <<'PY'
import sys, lookup as lk, name_table as nt
A,B,cmap,mmap,subs=lk.state()
cls=sys.argv[1].replace(".","/")
if cls not in A:
    hits=[c for c in A if c.rsplit("/",1)[-1]==cls]
    cls=hits[0] if len(hits)==1 else cls
print(f"{cls} -> {cmap.get(cls)}")
for spec in sys.argv[2:]:
    name,_,n=spec.partition("/"); n=int(n) if n else 0
    for (k,m,d,decl) in lk.visible(A,cls,name):
        if k!="M" or lk.arity(d)!=n: continue
        b,ev=lk.to_b(A,B,cmap,mmap,subs,k,m,d,decl)
        print(f"  {m}{d} -> {b}{nt.translate_desc(d,cmap)}  [{cmap.get(decl,'?').rsplit('/',1)[-1]}]{'' if ev=='Matcher' else ' ('+ev+')'}")
PY
