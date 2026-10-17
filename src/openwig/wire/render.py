"""wire_render.py - render the playing Bitwig project to a .wav by recording the system
loopback of Bitwig's audio output (real-time capture; the actual mastered output).

  from openwig.wire.render import render_to_wav
  render_to_wav(bridge, "out.wav", beats=128, tempo=128)

Plays the arrangement once from beat 0 (loop OFF) and records the system loopback for the
arrangement length; returns RMS and a silence flag. Backends per OS:
  Windows : WASAPI loopback of the default output endpoint (PyAudioWPatch)
  Linux   : the default sink's PulseAudio/PipeWire monitor source (`parecord`,
            from pulseaudio-utils; works on PipeWire via pipewire-pulse)
CLI: python wire_render.py out.wav BEATS TEMPO
"""
import subprocess
import math
import sys, time, wave, warnings
from pathlib import Path
import numpy as np


def _start_transport(bridge, tempo):
    """Position to start, disable loop so it plays through once. Play is the caller's
    job (each backend starts it relative to its own capture warm-up)."""
    if tempo is not None:
        bridge.request("transport.set_tempo", {"bpm": float(tempo)})
    bridge.request("transport.set_loop", {"on": False})
    bridge.request("transport.stop"); time.sleep(0.2)
    bridge.request("transport.set_position", {"beats": 0.0})


def _finish_wav(chunks, ch, sr, out_path):
    """Write captured s16le PCM to out_path + return the stats dict (rms proves sound)."""
    audio = np.frombuffer(b"".join(chunks), dtype=np.int16)
    rms = float(np.sqrt(np.mean((audio.astype(np.float64) / 32768.0) ** 2)))
    out_path = str(out_path)
    with wave.open(out_path, "wb") as w:
        w.setnchannels(ch); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(b"".join(chunks))
    if rms < 1e-3:
        warnings.warn("render captured silence: the song may be empty, or the default output "
                      "endpoint's loopback may be dead (e.g. an HDMI/TV sink that is off or muted). "
                      "Set a working default output device and retry.", RuntimeWarning, stacklevel=2)
    dur = len(audio) / ch / sr
    return {"path": out_path, "seconds": round(dur, 2), "rate": sr, "channels": ch,
            "rms": round(rms, 5), "silent": rms < 1e-3}


def _render_wasapi(bridge, out_path, beats, tempo, tail, lead, duration_seconds=None, set_tempo=True):
    """Windows: record the default WASAPI loopback endpoint."""
    # PyAudioWPatch is win32-only (pip marker). Import lazily so `import openwig`
    # (and the unit tests / linux CI) work everywhere.
    import pyaudiowpatch as pa

    secs = (beats / tempo * 60.0 if duration_seconds is None else duration_seconds) + tail
    p = pa.PyAudio()
    st = None
    try:
        dev = p.get_default_wasapi_loopback()
        sr = int(dev["defaultSampleRate"]); ch = int(dev["maxInputChannels"]); di = dev["index"]
        frames_per = 2048
        st = p.open(format=pa.paInt16, channels=ch, rate=sr, input=True,
                    input_device_index=di, frames_per_buffer=frames_per)
        _start_transport(bridge, tempo if set_tempo else None)
        # Warm up before playback, then discard the pre-playback audio.
        if lead:
            st.read(max(1, int(lead * sr)), exception_on_overflow=False)
        st.read(max(1, st.get_read_available()), exception_on_overflow=False)
        bridge.request("transport.play")
        chunks = []
        remaining = int(secs * sr)
        while remaining > 0:
            count = min(frames_per, remaining)
            chunks.append(st.read(count, exception_on_overflow=False))
            remaining -= count
    finally:
        try:
            bridge.request("transport.stop")
        finally:
            try:
                if st is not None:
                    try:
                        st.stop_stream()
                    finally:
                        st.close()
            finally:
                p.terminate()
    return _finish_wav(chunks, ch, sr, out_path)


def _parse_default_sink(pactl_info_output):
    """Pull the default sink name out of `pactl info` output (None if absent)."""
    for line in (pactl_info_output or "").splitlines():
        if line.lower().startswith("default sink:"):
            name = line.split(":", 1)[1].strip()
            if name:
                return name
    return None


def _default_monitor_source():
    """The monitor source of the default sink (what Bitwig's output plays into)."""
    sink = None
    try:  # pactl >= 14
        sink = subprocess.run(["pactl", "get-default-sink"], capture_output=True,
                              text=True, timeout=5).stdout.strip() or None
    except (OSError, subprocess.TimeoutExpired):
        pass
    if not sink:
        try:
            info = subprocess.run(["pactl", "info"], capture_output=True,
                                  text=True, timeout=5).stdout
            sink = _parse_default_sink(info)
        except (OSError, subprocess.TimeoutExpired):
            pass
    if not sink:
        raise RuntimeError("could not determine the default PulseAudio/PipeWire sink "
                           "(is pactl installed and a sound server running?)")
    return sink + ".monitor"


def _render_pulse(bridge, out_path, beats, tempo, tail, lead, duration_seconds=None, set_tempo=True):
    """Linux: record the default sink's monitor source as raw s16le via parecord
    (pulseaudio-utils; PipeWire systems serve it through pipewire-pulse)."""
    sr, ch = 48000, 2
    secs = (beats / tempo * 60.0 if duration_seconds is None else duration_seconds) + tail
    mon = _default_monitor_source()
    _start_transport(bridge, tempo if set_tempo else None)
    try:
        proc = subprocess.Popen(
            ["parecord", "--device", mon, "--raw",
             "--format=s16le", f"--rate={sr}", f"--channels={ch}"],
            stdout=subprocess.PIPE)
    except OSError as exc:
        raise RuntimeError("parecord not found - install pulseaudio-utils to render "
                           "on Linux") from exc
    try:
        def read_exact(n):
            out = []
            while n > 0:
                data = proc.stdout.read(min(n, 65536))
                if not data:
                    raise RuntimeError("parecord stream ended early (sound server gone?)")
                out.append(data); n -= len(data)
            return out

        read_exact(int(lead * sr) * ch * 2)  # discard capture warm-up before playback
        bridge.request("transport.play")
        chunks = read_exact(int(secs * sr) * ch * 2)
    finally:
        try:
            bridge.request("transport.stop")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
    return _finish_wav(chunks, ch, sr, out_path)


def render_to_wav(bridge, out_path, beats=128.0, tempo=128.0, tail=1.0, lead=0.3,
                  *, duration_seconds=None, set_tempo=True):
    """Record Bitwig's loopback output for one pass of the arrangement -> out_path (.wav)."""
    for name, value in (("beats", beats), ("tempo", tempo), ("tail", tail), ("lead", lead)):
        if not math.isfinite(value) or value < 0 or (name in ("beats", "tempo") and value == 0):
            raise ValueError(f"{name} must be finite and {'positive' if name in ('beats', 'tempo') else 'nonnegative'}")
    if duration_seconds is not None and (not math.isfinite(duration_seconds) or duration_seconds <= 0):
        raise ValueError("duration_seconds must be finite and positive")
    if sys.platform == "win32":
        return _render_wasapi(bridge, out_path, beats, tempo, tail, lead, duration_seconds, set_tempo)
    if sys.platform.startswith("linux"):
        return _render_pulse(bridge, out_path, beats, tempo, tail, lead, duration_seconds, set_tempo)
    raise RuntimeError(f"render() is not supported on {sys.platform} yet: it needs a "
                       "system loopback capture (macOS would need a virtual device "
                       "like BlackHole)")


if __name__ == "__main__":
    from openwig.bridge import BridgeClient
    out = sys.argv[1] if len(sys.argv) > 1 else "render.wav"
    beats = float(sys.argv[2]) if len(sys.argv) > 2 else 128.0
    tempo = float(sys.argv[3]) if len(sys.argv) > 3 else 128.0
    b = BridgeClient(); b.start(); assert b.wait_connected(8), "bridge not connected"
    print("rendering (real-time loopback capture)...")
    res = render_to_wav(b, out, beats, tempo)
    print(res)
    b.stop()
