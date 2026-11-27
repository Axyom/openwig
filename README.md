# openwig

**Control Bitwig Studio from an AI agent, through MCP, or from a panel inside Bitwig.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/Axyom/openwig/blob/master/LICENSE)

openwig is two mods for the Patchbay mod loader:

- **openwig** (`mods/openwig`): starts a local JSON-RPC bridge (`127.0.0.1:7778`) and an
  MCP server (`http://127.0.0.1:7779/mcp`) inside Bitwig. An agent can read and edit the
  open project: tracks, devices, plugins, modulators, Grid, clips, notes, automation,
  routing, transport, export and render. Each edit is one undo step.
- **openwig-agent** (`mods/openwig-agent`): an Agent panel inside Bitwig. You type a
  request, your own Claude Code does it through openwig, and every edit waits for your
  Allow. It can also write, build and install new mods.

Early alpha. Free and open source (GPL-3.0).

## Requirements

- Bitwig Studio **6.1.7**, Windows x64 (the mods are built against that exact version).
- Patchbay Mod Loader **0.36.0-dev.17** (BML). It ships the Panels framework the agent needs.
- For the agent panel: Claude Code installed and logged in (`claude` on PATH).

## Install

1. Download `openwig-<version>.zip` and `openwig-agent-<version>.zip` from the
   [releases page](https://github.com/Axyom/openwig/releases). Don't unpack them.
2. In Patchbay, go to **Mods**, click **Add Mod...** and pick both ZIPs.
3. Start Bitwig through Patchbay.

## Use with Claude Code (or any MCP client)

With Bitwig running:

```
claude mcp add -s user --transport http openwig http://127.0.0.1:7779/mcp
```

Claude Desktop: Settings, Connectors, add a custom connector with that URL.

## Build from source

Needs JDK 25, Patchbay's mod kit, and a compile jar made from your own Bitwig 6.1.7 install
(never committed or shared). `python build.py --release` writes
`scratch/release/openwig-<version>.zip` and `scratch/release/openwig-agent-<version>.zip`.
Each mod's README has the details.

## The old Python SDK

openwig used to be a Python library for composing Bitwig songs. It ended at PyPI
`openwig==0.1.3`; its last source state is the git tag
[`python-sdk-final`](https://github.com/Axyom/openwig/tree/python-sdk-final).
