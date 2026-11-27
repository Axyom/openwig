package io.github.axyom.openwigagent;

import bitwig.obf.dBz; // a button
import bitwig.obf.drf; // the application, which runs tasks on Bitwig's UI thread
import bitwig.obf.dXL; // a vertical or horizontal group of widgets
import bitwig.obf.eeu; // a scrolling multi-line text editor
import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;
import io.github.axyom.openwigagent.Transcript.Kind;
import java.nio.file.Path;

/**
 * The Agent panel: a message box, Send / Stop / New, and the transcript, which the mod paints itself (bubbles,
 * newest at the bottom, mouse wheel to scroll). The agent's tool calls run without asking.
 */
public final class AgentPanels implements PanelProvider {
    static final Bml.Mod LOG = Bml.mod("openwig-agent");
    private static final Transcript TRANSCRIPT = new Transcript(); // UI thread
    private static Agent agent;
    private static boolean streaming; // the newest entry is the agent's reply being written
    // Streamed text waiting for the UI thread: the reader appends, one scheduled flush shows it.
    private static final StringBuilder PENDING = new StringBuilder();
    private static boolean flushScheduled; // guarded by PENDING

    @Override
    public void declare(Panels panels) {
        panels.view("agent", "Agent")
              .icon("agent_icon")
              .content(context -> build())
              .side("browser")
              .sideColumnShare(0.35);
        start();
    }

    private static synchronized void start() {
        if (agent != null) return;
        Path dir = LOG.resource("data");
        agent = new Agent(dir, new Agent.View() {
            @Override public void text(String text) { queue(text); }
            @Override public void line(String line) { ui(() -> add(line)); }
            @Override public void busy(boolean busy) { if (!busy) ui(() -> { flush(); streaming = false; }); }
        });
        agent.killOnExit();
        try {
            ModTools.register(new ModBuilder(dir, LOG.resource("ecj")));
            LOG.log("agent panel ready; mod tools added to the bridge's MCP server");
        } catch (RuntimeException failure) {
            LOG.log("mod builder unavailable", failure);
        }
        Agent.daemon("openwig-agent-java2d", AgentPanels::warmJava2D);
        ui(() -> add("Type in the box above, then Enter or Send. Click a message to copy it."));
    }

    /** Java2D loads its fonts on first use (a few hundred ms): do it off the UI thread before the first paint. */
    private static void warmJava2D() {
        try {
            java.awt.Graphics2D g = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE).createGraphics();
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10));
            g.drawString("Java2D", 0, 8);
            g.getFontMetrics().stringWidth("Java2D");
            g.dispose();
        } catch (Throwable failure) {
            LOG.log("Java2D unavailable", failure);
        }
    }

    // ---- transcript (UI thread) -------------------------------------------------------------------------------

    /** On Bitwig's UI thread; dropped while Bitwig shuts down (the application is gone by then). */
    private static void ui(Runnable r) {
        drf app = drf.EO();
        if (app != null) app.asyncExec(r);
    }

    /** From the reader thread: streamed text, shown by one UI flush however many deltas arrive before it runs. */
    private static void queue(String text) {
        synchronized (PENDING) {
            PENDING.append(text);
            if (flushScheduled) return;
            flushScheduled = true;
        }
        ui(AgentPanels::flush);
    }

    private static void flush() {
        String text;
        synchronized (PENDING) {
            text = PENDING.toString();
            PENDING.setLength(0);
            flushScheduled = false;
        }
        if (!text.isEmpty()) stream(text);
    }

    /** A whole line from the agent or the panel: what the user sent, a tool call, or a status line. */
    private static void add(String line) {
        flush(); // streamed text that came before this line goes first
        streaming = false;
        if (line.startsWith("you: ")) TRANSCRIPT.add(Kind.USER, line.substring(5));
        else if (line.startsWith("  > ")) TRANSCRIPT.add(Kind.TOOL, line.substring(4));
        else TRANSCRIPT.add(Kind.NOTE, line);
    }

    private static void stream(String text) {
        if (!streaming) {
            String start = text.stripLeading();
            if (start.isEmpty()) return;
            TRANSCRIPT.add(Kind.AGENT, start);
            streaming = true;
            return;
        }
        TRANSCRIPT.append(text);
    }

    /** The length of each message, for the Java2D demo strip. */
    private static int[] lengths() {
        return TRANSCRIPT.entries().stream()
                .filter(e -> e.kind == Kind.USER || e.kind == Kind.AGENT)
                .mapToInt(e -> e.text.length()).toArray();
    }

    // ---- widgets ------------------------------------------------------------------------------------------------

    private static Object build() {
        TextValue input = new TextValue(true);
        Runnable send = () -> {
            String message = input.text().strip();
            ui(() -> input.set(""));
            if (message.isEmpty()) return;
            ui(() -> add("you: " + message));
            Agent.daemon("openwig-agent-send", () -> agent.send(message));
        };
        input.onEdit(text -> { if (text.endsWith("\n")) send.run(); }); // Enter sends

        dXL buttons = dXL.BBg(3); // a horizontal group with room for three buttons
        buttons.IvP(button("Send", send));
        buttons.IvP(button("Stop", () -> { Agent.daemon("openwig-agent-stop", agent::stop); add("[stopped]"); }));
        buttons.IvP(button("New", () -> { Agent.daemon("openwig-agent-new", agent::reset); TRANSCRIPT.clear(); add("New discussion."); }));

        dXL column = dXL.unz(4); // a vertical group with room for four widgets
        column.IvP(eeu.beI(input));      // the message box: a multi-line editor
        column.IvP(buttons);
        column.IvP(Java2DDemo.create(TRANSCRIPT, AgentPanels::lengths)); // a Java2D scene, drawn both ways
        column.beI(TranscriptView.create(TRANSCRIPT), 1f); // the transcript takes the rest of the height
        return column;
    }

    private static dBz button(String label, Runnable click) {
        dBz b = dBz.wLf(label); // a button with this label
        b.sCA(click);           // its click
        return b;
    }
}
