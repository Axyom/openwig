package io.github.axyom.openwigagent;

import bitwig.obf.dBz; // a button
import bitwig.obf.drf; // the application, which runs tasks on Bitwig's UI thread
import bitwig.obf.dXL; // a vertical or horizontal group of widgets
import bitwig.obf.eeu; // a scrolling multi-line text editor
import bml.api.Bml;
import bml.panels.PanelProvider;
import bml.panels.Panels;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Spike: a panel with a live transcript and a text input, to prove Bitwig's widgets can host a chat. */
public final class AgentPanels implements PanelProvider {
    static final Bml.Mod LOG = Bml.mod("openwig-agent");
    static final TextValue TRANSCRIPT = new TextValue("OpenWig Agent spike\n", false);
    private static ScheduledExecutorService ticker;

    @Override
    public void declare(Panels panels) {
        panels.view("agent", "Agent")
              .icon("agent_icon")
              .content(context -> build())
              .side("browser")
              .sideColumnShare(0.35);
        startTicker();
        LOG.log("agent panel declared");
    }

    static void append(String line) {
        drf.EO().asyncExec(() -> TRANSCRIPT.set(TRANSCRIPT.text() + line + "\n")); // on Bitwig's UI thread
    }

    private static synchronized void startTicker() {
        if (ticker != null) return;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "openwig-agent-spike");
            t.setDaemon(true);
            return t;
        });
        int[] n = {0};
        ticker.scheduleAtFixedRate(() -> {
            if (n[0] >= 150) return; // 30 s of ticks, then quiet
            append("tick " + (++n[0]) + " at " + LocalTime.now().withNano(0));
        }, 2, 200, TimeUnit.MILLISECONDS);
    }

    private static Object build() {
        TextValue input = new TextValue("", true);
        input.onEdit(text -> {
            if (!text.endsWith("\n")) return; // Enter sends
            String message = text.strip();
            drf.EO().asyncExec(() -> input.set(""));
            if (!message.isEmpty()) append("you: " + message);
            LOG.log("input sent: " + message.length() + " chars");
        });
        dXL column = dXL.unz(3); // a vertical group with room for three widgets
        column.IvP(eeu.beI(TRANSCRIPT)); // the transcript: a scrolling text bound to the transcript value
        column.IvP(eeu.beI(input));      // the input: a multi-line editor bound to the input value
        dBz send = dBz.wLf("Send");      // a button labelled Send
        send.sCA(() -> input.setValue(input.text() + "\n")); // the button's click: send as Enter does
        column.IvP(send);
        return column;
    }
}
