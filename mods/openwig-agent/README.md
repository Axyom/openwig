# Project: OpenWig Agent (Patchbay mod)

An agent panel inside Bitwig: you type a request, your own Claude Code does it through the OpenWig Bridge.

Needs: the OpenWig Bridge mod, Framework: Panels, and Claude Code installed and logged in (`claude` on PATH).

## Use

- Open it with the **Agent** button (chat icon) in the bottom bar, next to the browser panels. It is a column beside the browser.
- Type in the top box, then Enter or **Send**. Replies appear below in bubbles, newest at the bottom, with their Markdown rendered (headings, lists, quotes, bold, italic, inline code, and code blocks in the theme's Console font). Tool calls show as one compact line each.
- The view follows new text unless you scrolled up; sending a message brings it back to the bottom. Scroll with the mouse wheel (smoothly), with the bar on the right, or, once you clicked the transcript, with Up, Down, Page Up, Page Down, Home and End.
- Drag over text to select it, across messages too; double click selects a word, triple click a whole message. Ctrl+C (Cmd+C) copies the selection, Ctrl+A selects everything, Escape clears.
- Tool calls run without asking: the agent can only use OpenWig's tools, each edit is one undo step.
- **Stop** ends the current turn. **New** starts a fresh discussion. The discussion otherwise resumes, even after a restart.

## How it works

- Runs `claude -p` with stream-json, no built-in tools, only OpenWig's MCP tools, none of your CLAUDE.md or memory, on your Claude Code login.
- Claude Code talks to the bridge's MCP server (`127.0.0.1:7779/mcp`) directly. At startup this mod adds its `mod_*` tools to that server in Bitwig's JVM (`McpServer.addTool`), so any MCP client connected to the bridge gets them too while this mod is installed.
- The session ID and prompt files are kept in the mod's data folder (`%LOCALAPPDATA%\BML\mods\openwig-agent\agent`).

## Known gaps

- Markdown tables and images are shown as their text.
- Links are coloured but can't be opened from the transcript.
- No memory between discussions yet.

## Painting

The transcript is a widget the mod paints itself, not one of Bitwig's text widgets:

- `PaintedWidget` (a subclass of Bitwig's widget base): Bitwig asks it for its minimum, preferred and maximum size,
  calls its paint method with Bitwig's drawing surface, asks it for a handler when the wheel turns or a button is
  pressed over it (that handler also gets the drag, the release, and the cursor to show while the mouse is over it),
  and gives it key presses while it has the keyboard focus (a click on it takes the focus; Bitwig's shortcuts get
  the keys it doesn't use). `animate()` ticks it once per display frame; `repaint()` and `relayout()` ask Bitwig to
  paint or lay it out again.
- `Canvas`: the drawing surface's calls under readable names. It is Cairo's model (paths, fill, stroke, clip,
  save/restore, transforms, gradients, text with the theme's fonts, images from ARGB pixels), recorded as render
  commands that Bitwig's GPU renderer runs. Bitwig passes every colour drawn there through the user's contrast
  settings (Settings > User Interface > Contrast), as for its own widgets; images are drawn as they are.
- `Markdown` and `RichLayout` read the agent's Markdown and lay it out (word wrap with the theme fonts' own
  advances); they measure through an interface, so they also run outside Bitwig.

### Drawing with a Java2D library

Two bridges let code written for `java.awt.Graphics2D` (a chart, an SVG or HTML renderer) draw in a `PaintedWidget`:

- `CanvasGraphics2D`: a `Graphics2D` on Bitwig's surface. Shapes, strokes, gradients and text become Bitwig paths,
  so they stay vector, GPU-rendered and crisp at any UI scale. Make one in `paint` with the transform from the
  library's units to the widget's pixels, and dispose it at the end of `paint`:

  ```java
  @Override
  protected void paint(Canvas canvas, double width, double height) {
      double u = unit(); // pixels per layout unit
      CanvasGraphics2D g = new CanvasGraphics2D(canvas, new AffineTransform(u, 0, 0, u, 0, 0));
      try {
          chart.draw(g, new Rectangle2D.Double(0, 0, width / u, height / u)); // any Graphics2D code
      } finally {
          g.dispose();
      }
  }
  ```

  Text is drawn as the Java font's outlines, so it matches what the library measured. Not supported: XOR mode,
  `copyArea`, composites other than source-over with alpha.
- `Java2DBitmap`: the same code into a premultiplied `BufferedImage`, uploaded once as a Bitwig image and drawn
  from there until `invalidate()`. Use it for code that needs pixels, or to cache an expensive drawing:

  ```java
  private final Java2DBitmap bitmap = new Java2DBitmap(); // call bitmap.invalidate() when the data changes

  @Override
  protected void paint(Canvas canvas, double width, double height) {
      bitmap.paint(canvas, 0, 0, (int) width, (int) height, unit(), g -> chart.draw(g, area));
  }
  ```

  Drawing a whole 610 x 900 px panel this way took about 20 to 30 ms and its upload about 1 ms (Bitwig 6.1.7), so
  redraw on change, not every frame. Its colours skip Bitwig's contrast settings.

## Build

`python mods/openwig-agent/build.py --check` (builds the bridge first: this mod compiles against its `helper.jar`).

## Making mods (spike)

The agent can write a mod (`mod_write_file`), build it (`mod_build`) and install it (`mod_install`) with Bitwig's own Java only: a bundled Eclipse compiler (ECJ 3.44.0, EPL-2.0), Patchbay's kit, and Patchbay's offline check on a private loader copy. The mod loads the next time Bitwig starts. Verified from the command line; not yet run inside Bitwig.
