"""Offline build and protocol smoke test against an installed Bitwig 6.1.5."""

from __future__ import annotations

import json
import socket
import struct
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

from build import ACCESS_CLASS, BRIDGE_CLASS, MAIN_CLASS, SONG_CLASS, UNDO_CLASS, build

BITWIG_ROOT = Path(r"C:\Program Files\Bitwig Studio")
JDK_BIN = Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot\bin")


def receive_line(connection: socket.socket) -> dict:
    data = bytearray()
    while not data.endswith(b"\n"):
        data.extend(connection.recv(1))
    return json.loads(data)


def request(connection: socket.socket, method: str, identifier: int) -> dict:
    body = json.dumps({"jsonrpc": "2.0", "id": identifier, "method": method}).encode()
    connection.sendall(struct.pack(">I", len(body)) + body)
    return receive_line(connection)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="openwig-java-smoke-") as temp:
        work = Path(temp)
        patched = work / "bitwig.jar"
        build(BITWIG_ROOT, patched, JDK_BIN)
        with zipfile.ZipFile(BITWIG_ROOT / "bin" / "bitwig.jar") as original, \
             zipfile.ZipFile(patched) as result:
            assert set(result.namelist()) - set(original.namelist()) == {BRIDGE_CLASS, ACCESS_CLASS, SONG_CLASS, UNDO_CLASS}
            assert {name for name in original.namelist()
                    if original.read(name) != result.read(name)} == {MAIN_CLASS}
        probe = work / "BridgeProbe.java"
        probe.write_text("""import com.openwig.bridge.Bootstrap;
public class BridgeProbe {
    public static void main(String[] args) throws Exception {
        Bootstrap.start();
        Thread.sleep(15000);
    }
}
""", encoding="utf-8")
        classes = work / "classes"
        classes.mkdir()
        libs = BITWIG_ROOT / "bin" / "libs.jar"
        subprocess.run([str(JDK_BIN / "javac"), "-proc:none", "-cp", str(libs),
                        "-d", str(classes), str(Path(__file__).parent / "src" / "com" / "openwig" /
                        "bridge" / "Bootstrap.java"),
                        str(Path(__file__).parent / "stubs" / "com" / "openwig" /
                            "bridge" / "DirectAccess.java"), str(probe)], check=True)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        process = subprocess.Popen([str(JDK_BIN / "java"),
                                    f"-Dopenwig.bridge.port={port}", "-cp",
                                    f"{classes};{libs}", "BridgeProbe"])
        try:
            for attempt in range(40):
                try:
                    connection = socket.create_connection(("127.0.0.1", port), 0.25)
                    break
                except OSError:
                    time.sleep(0.1)
            else:
                raise AssertionError("Java bridge did not start")
            with connection:
                assert receive_line(connection)["method"] == "connected"
                assert request(connection, "ping", 1)["result"] == "pong"
                assert request(connection, "host.version", 2)["result"]["version"] == "6.1.5"
                assert request(connection, "not.implemented", 3)["error"]["code"] == -32601
            print("offline bridge smoke: PASS")
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == "__main__":
    main()
