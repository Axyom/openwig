package io.github.axyom.openwigagent;

import bitwig.obf.dBz; // a button
import bitwig.obf.drf; // the application, which runs tasks on Bitwig's UI thread
import bitwig.obf.dXL; // a vertical or horizontal group of widgets
import bitwig.obf.eeu; // a scrolling multi-line text editor
import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The Agent panel: a transcript (newest message first, so new text is always in view), a message box, and
 * Send / Stop / New. The agent's tool calls run without asking.
 */
public final class AgentPanels implements PanelProvider {
    static final Bml.Mod LOG = Bml.mod("openwig-agent");
    private static final TextValue TRANSCRIPT = new TextValue(false);
    private static final ArrayDeque<StringBuilder> ENTRIES = new ArrayDeque<>(); // oldest first; shown newest first
    private static final int KEEP = 200;
    private static Agent agent;
    private static boolean streaming; // the newest entry is the agent's reply being written
    private static String older = "";  // rendered entries except the newest (UI thread)
    private static boolean olderStale;
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
        ui(() -> add("Type in the box above, then Enter or Send."));
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

    private static void add(String line) {
        flush(); // streamed text that came before this line goes first
        ENTRIES.add(new StringBuilder(line));
        olderStale = true;
        streaming = false;
        render();
    }

    private static void stream(String text) {
        if (!streaming) {
            String start = text.stripLeading();
            if (start.isEmpty()) return;
            ENTRIES.add(new StringBuilder("agent: "));
            olderStale = true;
            streaming = true;
            text = start;
        }
        ENTRIES.peekLast().append(text);
        render();
    }

    private static void render() {
        while (ENTRIES.size() > KEEP) ENTRIES.pollFirst();
        Iterator<StringBuilder> newestFirst = ENTRIES.descendingIterator();
        if (!newestFirst.hasNext()) { TRANSCRIPT.set(""); return; }
        String newest = newestFirst.next().toString().strip() + "\n\n";
        if (olderStale) {
            StringBuilder out = new StringBuilder();
            while (newestFirst.hasNext()) out.append(newestFirst.next().toString().strip()).append("\n\n");
            older = out.toString();
            olderStale = false;
        }
        TRANSCRIPT.set(newest + older);
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
        buttons.IvP(button("New", () -> { Agent.daemon("openwig-agent-new", agent::reset); ENTRIES.clear(); add("New discussion."); }));

        dXL column = dXL.unz(3); // a vertical group with room for three widgets
        column.IvP(eeu.beI(input));      // the message box: a multi-line editor
        column.IvP(buttons);
        column.IvP(eeu.beI(TRANSCRIPT)); // the transcript, newest first
        return column;
    }

    private static dBz button(String label, Runnable click) {
        dBz b = dBz.wLf(label); // a button with this label
        b.sCA(click);           // its click
        return b;
    }
}
