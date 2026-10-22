"""Capture failures must not leave Bitwig playing or audio resources open."""

import sys
from types import SimpleNamespace
import wave

import pytest

from openwig.wire.render import render_to_wav


class Capture:
    def __init__(self, fail=False):
        self.fail = fail
        self.reads = 0
        self.stopped = self.closed = self.terminated = False

    def get_default_wasapi_loopback(self):
        return {"defaultSampleRate": 1000, "maxInputChannels": 2, "index": 0}

    def open(self, **_kwargs):
        return self

    def get_read_available(self):
        return 0

    def read(self, frames, **_kwargs):
        self.reads += 1
        if self.fail and self.reads >= 3:
            raise OSError("capture device disconnected")
        return b"\x00\x20" * frames * 2

    def stop_stream(self):
        self.stopped = True

    def close(self):
        self.closed = True

    def terminate(self):
        self.terminated = True


def install_capture(monkeypatch, capture):
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setitem(sys.modules, "pyaudiowpatch", SimpleNamespace(
        PyAudio=lambda: capture, paInt16=8))
    monkeypatch.setattr("openwig.wire.render.time.sleep", lambda _seconds: None)


def test_capture_writes_exact_duration_and_stops(tmp_path, monkeypatch):
    capture = Capture()
    install_capture(monkeypatch, capture)
    calls = []
    bridge = SimpleNamespace(request=lambda method, *args: calls.append(method))
    path = tmp_path / "render.wav"
    result = render_to_wav(bridge, path, beats=1, tempo=120, tail=0.25, lead=0)
    with wave.open(str(path)) as wav:
        assert wav.getnframes() == 750
        assert wav.getnchannels() == 2
    assert result["rms"] == 0.25
    assert not result["silent"]
    assert calls[-1] == "transport.stop"
    assert capture.stopped and capture.closed and capture.terminated


def test_capture_failure_stops_transport_and_releases_device(tmp_path, monkeypatch):
    capture = Capture(fail=True)
    install_capture(monkeypatch, capture)
    calls = []
    bridge = SimpleNamespace(request=lambda method, *args: calls.append(method))
    with pytest.raises(OSError, match="disconnected"):
        render_to_wav(bridge, tmp_path / "failed.wav", beats=1, tempo=120)
    assert "transport.play" in calls
    assert calls[-1] == "transport.stop"
    assert capture.stopped and capture.closed and capture.terminated


def test_tempo_map_duration_does_not_overwrite_automated_tempo(tmp_path, monkeypatch):
    capture = Capture()
    install_capture(monkeypatch, capture)
    calls = []
    bridge = SimpleNamespace(request=lambda method, *args: calls.append(method))
    result = render_to_wav(bridge, tmp_path / "tempo.wav", beats=4, tempo=120,
                           tail=0, lead=0, duration_seconds=3, set_tempo=False)
    assert result["seconds"] == 3
    assert "transport.set_tempo" not in calls


@pytest.mark.parametrize("kwargs", [{"beats": 0}, {"tempo": float("nan")}, {"tail": -1}])
def test_invalid_capture_duration_does_not_start_transport(tmp_path, kwargs):
    bridge = SimpleNamespace(request=lambda *_args: pytest.fail("transport was touched"))
    with pytest.raises(ValueError):
        render_to_wav(bridge, tmp_path / "invalid.wav", **kwargs)
