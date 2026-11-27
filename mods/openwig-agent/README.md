# Project: OpenWig Agent (Patchbay mod)

An agent panel inside Bitwig: you type a request, your own Claude Code does it through the OpenWig Bridge, and every edit waits for your Allow.

Needs: the OpenWig Bridge mod, Framework: Panels, and Claude Code installed and logged in (`claude` on PATH).

## Use

- Open it with the **Agent** button (chat icon) in the bottom bar, next to the browser panels. It is a column beside the browser.
- Type in the top box, then Enter or **Send**. Replies appear below, newest first.
- An edit shows `ALLOW? <method> <params>`: press **Allow** or **Deny**. Reads run without asking.
- **Stop** ends the current turn. **New** starts a fresh discussion. The discussion otherwise resumes, even after a restart.

## How it works

- Runs `claude -p` with stream-json, no built-in tools, only OpenWig's MCP tools, none of your CLAUDE.md or memory, on your Claude Code login.
- Claude Code talks to a small gate in this mod (`127.0.0.1:7780/mcp`), which forwards to the bridge (`7779`). The gate holds any `bitwig_call` of a method the bridge lists as `[edit]` until you answer. It lives in this mod so the bridge stays unchanged.
- The session ID and prompt files are kept in the mod's data folder (`%LOCALAPPDATA%\BML\mods\openwig-agent\agent`).

## Known gaps

- The transcript is newest-first because Bitwig's text widget doesn't scroll itself to new text.
- No memory between discussions yet.

## Build

`python mods/openwig-agent/build.py --check` (build the bridge first with `mods/openwig/build.py`).

## Making mods (spike)

The agent can write a mod (`mod_write_file`), build it (`mod_build`) and install it (`mod_install`, asks Allow) with Bitwig's own Java only: a bundled Eclipse compiler (ECJ 3.44.0, EPL-2.0), Patchbay's kit, and Patchbay's offline check on a private loader copy. The mod loads the next time Bitwig starts. Verified from the command line; not yet run inside Bitwig.
