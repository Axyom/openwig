# Project: OpenWig Agent (Patchbay mod)

An agent panel inside Bitwig: you type a request, your own Claude Code does it through the OpenWig Bridge.

Needs: the OpenWig Bridge mod, Framework: Panels, and Claude Code installed and logged in (`claude` on PATH).

## Use

- Open it with the **Agent** button (chat icon) in the bottom bar, next to the browser panels. It is a column beside the browser.
- Type in the top box, then Enter or **Send**. Replies appear below, newest first.
- Tool calls run without asking: the agent can only use OpenWig's tools, each edit is one undo step.
- **Stop** ends the current turn. **New** starts a fresh discussion. The discussion otherwise resumes, even after a restart.

## How it works

- Runs `claude -p` with stream-json, no built-in tools, only OpenWig's MCP tools, none of your CLAUDE.md or memory, on your Claude Code login.
- Claude Code talks to the bridge's MCP server (`127.0.0.1:7779/mcp`) directly. At startup this mod adds its `mod_*` tools to that server in Bitwig's JVM (`McpServer.addTool`), so any MCP client connected to the bridge gets them too while this mod is installed.
- The session ID and prompt files are kept in the mod's data folder (`%LOCALAPPDATA%\BML\mods\openwig-agent\agent`).

## Known gaps

- The transcript is newest-first because Bitwig's text widget doesn't scroll itself to new text.
- No memory between discussions yet.

## Build

`python mods/openwig-agent/build.py --check` (builds the bridge first: this mod compiles against its `helper.jar`).

## Making mods (spike)

The agent can write a mod (`mod_write_file`), build it (`mod_build`) and install it (`mod_install`) with Bitwig's own Java only: a bundled Eclipse compiler (ECJ 3.44.0, EPL-2.0), Patchbay's kit, and Patchbay's offline check on a private loader copy. The mod loads the next time Bitwig starts. Verified from the command line; not yet run inside Bitwig.
