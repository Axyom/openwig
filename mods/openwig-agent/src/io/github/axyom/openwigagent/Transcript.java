package io.github.axyom.openwigagent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The discussion shown in the Agent panel, oldest first: what the user sent, the agent's replies (streamed into the
 * newest one), tool calls and status lines. UI thread only. Every view showing it is told when it changes.
 */
final class Transcript {
    enum Kind { USER, AGENT, TOOL, NOTE }

    static final class Entry {
        final Kind kind;
        final StringBuilder text;
        int version; // bumped on every change, so views re-wrap only what changed

        Entry(Kind kind, String text) {
            this.kind = kind;
            this.text = new StringBuilder(text);
        }
    }

    private static final int KEEP = 200;
    private final List<Entry> entries = new ArrayList<>();
    private final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private int version;

    List<Entry> entries() { return entries; }
    int version() { return version; }

    Entry add(Kind kind, String text) {
        Entry e = new Entry(kind, text);
        entries.add(e);
        while (entries.size() > KEEP) entries.remove(0);
        changed();
        return e;
    }

    /** Appends to the newest entry (it must exist). */
    void append(String text) {
        Entry e = entries.get(entries.size() - 1);
        e.text.append(text);
        e.version++;
        changed();
    }

    Entry newest() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }

    void clear() {
        entries.clear();
        changed();
    }

    /** A view to tell of changes; held weakly, so a view Bitwig disposes is dropped. */
    void listen(Runnable view) {
        listeners.add(new WeakReference<>(view));
    }

    private void changed() {
        version++;
        listeners.removeIf(ref -> ref.get() == null);
        for (WeakReference<Runnable> ref : List.copyOf(listeners)) {
            Runnable view = ref.get();
            if (view != null) view.run();
        }
    }
}
