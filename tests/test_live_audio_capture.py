"""Calibrate the spectral check used by manual deleted-track regressions."""
import numpy as np
import pytest

from live_audio_capture import tone_rms


def test_tone_rms_detects_a_lingering_pitched_clip():
    rate = 48000
    t = np.arange(rate * 4) / rate
    data = .05 * np.sin(2 * np.pi * 659.255 * t)
    assert tone_rms(data, rate, 2.1, 659.255) == pytest.approx(.05 / np.sqrt(2), rel=.08)
    assert tone_rms(data, rate, 2.1, 440) < .001


def test_tone_rms_allows_metronome_clicks_after_clear():
    rate = 48000
    t = np.arange(rate * 4) / rate
    clicks = .03 * np.sin(2 * np.pi * 800 * t) * ((t % .4) < .025)
    for start in (1.1, 1.6, 2.1, 2.6, 3.1, 3.5):
        for frequency in (220, 440, 659.255, 880, 1318.51, 1760):
            assert tone_rms(clicks, rate, start, frequency) < .001
