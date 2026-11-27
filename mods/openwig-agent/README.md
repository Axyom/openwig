# Project: OpenWig Agent (Patchbay mod)

An agent panel inside Bitwig: you type a request, your own Claude Code does it through the OpenWig Bridge.

Needs: the OpenWig Bridge mod, Framework: Panels, and Claude Code installed and logged in (`claude` on PATH).

## Use

- Open it with the **Agent** button (chat icon) in the bottom bar, next to the browser panels. It is a column beside the browser.
- Type in the top box, then Enter or **Send**. Replies appear below in bubbles, newest at the bottom; the view follows new text while it is scrolled to the bottom. Scroll with the mouse wheel, by dragging the messages, or with the bar on the right. Click a message to copy it.
- `/demo` in the box fills the transcript with sample messages (nothing is sent), to try the view.
- Tool calls run without asking: the agent can only use OpenWig's tools, each edit is one undo step.
- **Stop** ends the current turn. **New** starts a fresh discussion. The discussion otherwise resumes, even after a restart.

## How it works

- Runs `claude -p` with stream-json, no built-in tools, only OpenWig's MCP tools, none of your CLAUDE.md or memory, on your Claude Code login.
- Claude Code talks to the bridge's MCP server (`127.0.0.1:7779/mcp`) directly. At startup this mod adds its `mod_*` tools to that server in Bitwig's JVM (`McpServer.addTool`), so any MCP client connected to the bridge gets them too while this mod is installed.
- The session ID and prompt files are kept in the mod's data folder (`%LOCALAPPDATA%\BML\mods\openwig-agent\agent`).

## Known gaps

- The strip between the buttons and the transcript is a spike demo: one Java2D scene drawn as vector on Bitwig's surface (left) and as an uploaded bitmap (right), with their costs.
- Text in the transcript can't be selected; a click copies a whole message.
- No memory between discussions yet.

## Painting (spike)

The transcript is a widget the mod paints itself (`PaintedWidget`, a subclass of Bitwig's widget base), not one of
Bitwig's text widgets:

- `PaintedWidget`: Bitwig asks it for its minimum, preferred and maximum size, calls its paint method with Bitwig's
  drawing surface, and asks it for a handler when the wheel turns or a button is pressed over it (the press handler
  also gets the drag and the release). `repaint()` and `relayout()` ask Bitwig to paint or lay it out again.
- `Canvas`: the drawing surface's calls under readable names. It is Cairo's model (paths, fill, stroke, clip,
  save/restore, transforms, gradients, text with the theme's fonts, images from ARGB pixels), recorded as render
  commands that Bitwig's GPU renderer runs.
- `CanvasGraphics2D`: a `java.awt.Graphics2D` on that surface, so Java2D drawing code renders as vector and crisp at
  any UI scale. `Java2DBitmap`: Java2D into a `BufferedImage`, uploaded as an image, for code that needs pixels.

## Build

`python mods/openwig-agent/build.py --check` (builds the bridge first: this mod compiles against its `helper.jar`).

## Making mods (spike)

The agent can write a mod (`mod_write_file`), build it (`mod_build`) and install it (`mod_install`) with Bitwig's own Java only: a bundled Eclipse compiler (ECJ 3.44.0, EPL-2.0), Patchbay's kit, and Patchbay's offline check on a private loader copy. The mod loads the next time Bitwig starts. Verified from the command line; not yet run inside Bitwig.
