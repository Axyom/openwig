"""Manual live-test capture; optional Windows process loopback excludes other apps."""
import subprocess
import time
import wave
from pathlib import Path

import numpy as np


def capture(song, file, *, capture_exe=None, engine_pid=None, on_play=None):
    file = Path(file)
    if capture_exe is None:
        if on_play is not None:
            raise ValueError("on_play requires process capture")
        song.render(file, tail=.3)
    else:
        assert engine_pid and engine_pid > 0, "pass the disposable audio-engine PID"
        song.stop()
        song.b.request("transport.set_loop", {"on": False})
        song.b.request("transport.set_position", {"beats": 0.0})
        seconds = song.b.request("transport.beats_to_seconds", {"beats": song.total})["seconds"] + .6
        ready = file.with_suffix(".ready")
        assert not ready.exists() and not file.exists(), "use a fresh capture destination"
        process = subprocess.Popen([str(Path(capture_exe).resolve()), str(engine_pid),
                                    str(file.resolve()), str(seconds), str(ready.resolve())])
        try:
            deadline = time.monotonic() + 12
            while not ready.exists():
                assert process.poll() is None, "process loopback activation failed"
                assert time.monotonic() < deadline, "capture did not become ready"
                time.sleep(.01)
            song.b.request("transport.play")
            if on_play is not None:
                on_play()
            assert process.wait(timeout=seconds + 12) == 0, "process capture failed"
        finally:
            try:
                song.stop()
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)
    with wave.open(str(file), "rb") as w:
        rate, channels = w.getframerate(), w.getnchannels()
        assert w.getsampwidth() == 2
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, channels).mean(axis=1) / 32768.
    return rate, data


def tone_rms(data, rate, start, frequency):
    """Estimate a steady tone from a Hann-windowed quarter second."""
    segment = data[int(start * rate):int((start + .25) * rate)]
    window = np.hanning(len(segment))
    spectrum = np.abs(np.fft.rfft(segment * window))
    frequencies = np.fft.rfftfreq(len(segment), 1 / rate)
    band = abs(frequencies - frequency) <= 8
    return float(spectrum[band].max() * 2 / window.sum() / np.sqrt(2))
