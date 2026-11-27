#!/bin/bash
# Auto-match Bitwig 6.1.5 (A) against 6.1.7 (B) with FabricMC Matcher's CLI.
# Names of 1-3 characters are Bitwig's obfuscated ones; longer names are readable
# and anchor the matching. Runs through memguard (low priority, memory stop).
set -e
SDK=/c/Users/Axyom/Documents/git_repositories/bitwig-sdk
OUT="C:\Users\Axyom\Desktop\Bitwig Recomp\openwig\scratch\bitwig-615-617.match"
LIB="$(cygpath -w "$SDK/scratch/tools/Matcher/matcher-cli/build/install/matcher-cli/lib")"
JAVA="/c/Program Files/Eclipse Adoptium/jdk-21.0.3.9-hotspot/bin/java.exe"
CLS='^(?:.*/)?[^/$]{4,}(?:\$.*)?$'
MEM='^[^(;]*/(?:[^/(;]{4,}|run|get|set|add|put|of|is)(?:\(|;;).*$'
cd "$SDK"
# java directly (not the .bat launcher): cmd.exe would parse the | in the patterns
python scratch/recreate/memguard.py --need 8000 --floor 4000 --timeout 7200 -- \
  "$JAVA" -Xmx16g -p "$LIB" -m matcher.cli/matcher.cli.Main automatch \
  --inputs-a "C:\Users\Axyom\Desktop\Bitwig Recomp\openwig\scratch\match-a-615.jar" \
  --inputs-b "C:\Users\Axyom\Desktop\Bitwig Recomp\openwig\scratch\match-b-617.jar" \
  --shared-classpath "C:\Program Files\Bitwig Studio\bin\libs.jar" \
  --non-obfuscated-class-pattern-a "$CLS" --non-obfuscated-class-pattern-b "$CLS" \
  --non-obfuscated-member-pattern-a "$MEM" --non-obfuscated-member-pattern-b "$MEM" \
  --passes 3 \
  --output-file "$OUT"
