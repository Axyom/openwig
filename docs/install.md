# Install

## Requirements

- **Bitwig Studio 6**
- **Python 3.11+**
- **Windows** (fully supported) or **Linux** (experimental; `render()` additionally
  needs `parecord` from pulseaudio-utils, which PipeWire systems provide via
  pipewire-pulse). Linux support has not yet been validated against a live Bitwig -
  [reports welcome](https://github.com/Axyom/openwig/issues).

## 1. Install the Python package

```bash
pip install openwig
```

## 2. Install the controller script

```bash
python -m openwig install
```

This copies the bundled `openwig_bridge.control.js` into Bitwig's controller
scripts directory (`%USERPROFILE%\Documents\Bitwig Studio\Controller Scripts\`),
and the bootstrap symbol mapping (`symbols_default.json`) into the openwig data
dir (`%LOCALAPPDATA%\openwig\`). Both are required: the controller hardcodes no
obfuscated names, so without the data file it cannot resolve anything.

!!! tip "If install says the directory doesn't exist"
    Launch Bitwig Studio once (so it creates its user directory), then re-run.

## 3. Enable the controller in Bitwig

1. Open Bitwig Studio.
2. **Settings -> Controllers -> openwig -> Add -> OpenwigBridge**.
3. One-time. Bitwig remembers it across launches.

## 4. Version check

The controller's obfuscated-name mapping (`symbols_default.json`) is captured
from a specific Bitwig build. If you hit reflection errors after a Bitwig
update, that build is unsupported: please
[open an issue](https://github.com/Axyom/openwig/issues).

Then write your [first song](quickstart.md).

## Running the tests

Unit tests need no Bitwig and run anywhere:

```bash
pip install -e .[dev]
pytest
```

The live tests drive a real Bitwig. They require Bitwig running
with the OpenwigBridge controller enabled and are opt-in:

```bash
OPENWIG_LIVE=1 pytest -m live
```

Without `OPENWIG_LIVE=1` the live tests are skipped, so a plain `pytest` (and CI) never
touches a running Bitwig.

## Uninstall

```bash
python -m openwig uninstall   # removes the controller .js (you keep the pip package)
pip uninstall openwig
```
