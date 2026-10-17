"""Build an isolated Bitwig 6.1.5 JAR with the openwig Java bridge bootstrap."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ORIGINAL_SHA256 = "d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49"
MAIN_CLASS = "com/bitwig/flt/app/BitwigStudioMain.class"
BRIDGE_CLASS = "com/openwig/bridge/Bootstrap.class"
ACCESS_CLASS = "com/openwig/bridge/DirectAccess.class"
SONG_CLASS = "OpenwigDocument.class"
UNDO_CLASS = "com/bitwig/ramona/core/OpenwigUndo.class"
HERE = Path(__file__).resolve().parent


def build(bitwig_root: Path, output: Path, jdk_bin: Path) -> None:
    source = bitwig_root / "bin" / "bitwig.jar"
    libs = bitwig_root / "bin" / "libs.jar"
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != ORIGINAL_SHA256:
        raise ValueError(f"unsupported bitwig.jar SHA-256: {digest}")
    if output.resolve() == source.resolve():
        raise ValueError("output must not overwrite the installed JAR")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="openwig-java-build-") as temp:
        work = Path(temp)
        classes = work / "classes"
        classes.mkdir()
        patch_source = HERE / "src" / "com" / "openwig" / "bridge" / "PatchMain.java"
        bootstrap_source = HERE / "src" / "com" / "openwig" / "bridge" / "Bootstrap.java"
        subprocess.run([str(jdk_bin / "javac"), "-proc:none", "-cp", str(libs),
                        "-d", str(classes), str(patch_source)], check=True)
        original_class = work / "BitwigStudioMain.class"
        patched_class = work / "BitwigStudioMain.patched.class"
        access_class = work / "DirectAccess.class"
        undo_class = work / "OpenwigUndo.class"
        with zipfile.ZipFile(source) as jar:
            original_class.write_bytes(jar.read(MAIN_CLASS))
        subprocess.run([str(jdk_bin / "java"), "-cp", f"{classes};{libs}",
                        "com.openwig.bridge.PatchMain", str(original_class),
                        str(patched_class), str(access_class), str(undo_class)], check=True)
        access_stub = HERE / "stubs" / "com" / "openwig" / "bridge" / "DirectAccess.java"
        subprocess.run([str(jdk_bin / "javac"), "-proc:none", "-cp", f"{classes};{libs}",
                        "-d", str(classes), str(bootstrap_source), str(access_stub)], check=True)
        bitwig_stubs = sorted((HERE / "stubs" / "bitwig").rglob("*.java"))
        subprocess.run([str(jdk_bin / "javac"), "-proc:none", "-cp", f"{classes};{libs}",
                        "-d", str(classes), str(HERE / "src" / "OpenwigDocument.java"),
                        str(access_stub), *map(str, bitwig_stubs)], check=True)
        shutil.copy2(access_class, classes / ACCESS_CLASS)
        (classes / UNDO_CLASS).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(undo_class, classes / UNDO_CLASS)
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, "w") as patched:
            for item in original.infolist():
                contents = (patched_class.read_bytes() if item.filename == MAIN_CLASS
                            else original.read(item.filename))
                patched.writestr(item, contents)
            patched.write(classes / BRIDGE_CLASS, BRIDGE_CLASS,
                          compress_type=zipfile.ZIP_DEFLATED)
            patched.write(classes / ACCESS_CLASS, ACCESS_CLASS,
                          compress_type=zipfile.ZIP_DEFLATED)
            patched.write(classes / SONG_CLASS, SONG_CLASS,
                          compress_type=zipfile.ZIP_DEFLATED)
            patched.write(classes / UNDO_CLASS, UNDO_CLASS,
                          compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(output) as result:
        if result.testzip() is not None:
            raise ValueError("patched JAR failed ZIP integrity check")
        if {BRIDGE_CLASS, ACCESS_CLASS, SONG_CLASS, UNDO_CLASS} - set(result.namelist()):
            raise ValueError("bridge classes missing from patched JAR")
    print(f"built {output}")
    print(f"source SHA-256 {digest}")
    print(f"output SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bitwig-root", type=Path,
                        default=Path(r"C:\Program Files\Bitwig Studio"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jdk-bin", type=Path,
                        default=Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot\bin"))
    args = parser.parse_args()
    build(args.bitwig_root, args.output, args.jdk_bin)


if __name__ == "__main__":
    main()
