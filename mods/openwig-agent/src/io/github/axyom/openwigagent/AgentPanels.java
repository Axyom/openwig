package io.github.axyom.openwigagent;

import bitwig.obf.dBz; // a button
import bitwig.obf.drf; // the application, which runs tasks on Bitwig's UI thread
import bitwig.obf.dXL; // a vertical or horizontal group of widgets
import bitwig.obf.eeu; // a scrolling multi-line text editor
import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/**
 * The Agent panel: a transcript (newest message first, so new text is always in view), a message box, and
 * Send / Stop / New, plus Allow / Deny for the edit the agent is waiting on.
 */
public final class AgentPanels implements PanelProvider {
    static final Bml.Mod LOG = Bml.mod("openwig-agent");
    private static final TextValue TRANSCRIPT = new TextValue("", false);
    private static final List<StringBuilder> ENTRIES = new ArrayList<>(); // oldest first; shown newest first
    private static final int KEEP = 200;
    private static Agent agent;
    private static CompletableFuture<Boolean> pending;
    private static boolean streaming; // the newest entry is the agent's reply being written

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
            @Override public void text(String text) { ui(() -> stream(text)); }
            @Override public void line(String line) { ui(() -> add(line)); }
            @Override public void busy(boolean busy) { if (!busy) ui(() -> streaming = false); }
        });
        agent.killOnExit();
        try {
            ModBuilder mods = null;
            try { mods = new ModBuilder(dir, LOG.resource("ecj")); }
            catch (RuntimeException failure) { LOG.log("mod builder unavailable", failure); }
            new GateProxy(AgentPanels::ask, mods).start();
            LOG.log("agent panel ready; gate on http://127.0.0.1:" + GateProxy.PORT + "/mcp");
        } catch (Exception failure) {
            LOG.log("agent gate failed to start", failure);
        }
        ui(() -> add("Type in the box above, then Enter or Send. Edits wait for Allow."));
    }

    /** An edit the agent wants to make: shown with Allow / Deny, answered by the buttons. */
    private static CompletableFuture<Boolean> ask(String method, JSONObject params) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        ui(() -> {
            if (pending != null) pending.complete(false); // only one question at a time
            pending = answer;
            streaming = false;
            add("ALLOW? " + method + (params.isEmpty() ? "" : " " + params) + "   (Allow / Deny below)");
        });
        return answer;
    }

    private static void answer(boolean allow) {
        if (pending == null) return;
        CompletableFuture<Boolean> p = pending;
        pending = null;
        add(allow ? "  allowed" : "  denied");
        p.complete(allow);
    }

    // ---- transcript (UI thread) -------------------------------------------------------------------------------

    /** On Bitwig's UI thread; dropped while Bitwig shuts down (the application is gone by then). */
    private static void ui(Runnable r) {
        drf app = drf.EO();
        if (app != null) app.asyncExec(r);
    }

    private static void add(String line) {
        ENTRIES.add(new StringBuilder(line));
        streaming = false;
        render();
    }

    private static void stream(String text) {
        if (!streaming) {
            if (text.isBlank()) return;
            ENTRIES.add(new StringBuilder("agent: "));
            streaming = true;
        }
        ENTRIES.get(ENTRIES.size() - 1).append(text);
        render();
    }

    private static void render() {
        while (ENTRIES.size() > KEEP) ENTRIES.remove(0);
        StringBuilder out = new StringBuilder();
        for (int i = ENTRIES.size() - 1; i >= 0; i--) out.append(ENTRIES.get(i).toString().strip()).append("\n\n");
        TRANSCRIPT.set(out.toString());
    }

    // ---- widgets ------------------------------------------------------------------------------------------------

    private static Object build() {
        TextValue input = new TextValue("", true);
        Runnable send = () -> {
            String message = input.text().strip();
            ui(() -> input.set(""));
            if (message.isEmpty()) return;
            ui(() -> add("you: " + message));
            Agent.daemon("openwig-agent-send", () -> agent.send(message));
        };
        input.onEdit(text -> { if (text.endsWith("\n")) send.run(); }); // Enter sends

        dXL buttons = dXL.BBg(5); // a horizontal group with room for five buttons
        buttons.IvP(button("Send", send));
        buttons.IvP(button("Allow", () -> answer(true)));
        buttons.IvP(button("Deny", () -> answer(false)));
        buttons.IvP(button("Stop", () -> { answer(false); Agent.daemon("openwig-agent-stop", agent::stop); add("[stopped]"); }));
        buttons.IvP(button("New", () -> { answer(false); Agent.daemon("openwig-agent-new", agent::reset); ENTRIES.clear(); add("New discussion."); }));

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
